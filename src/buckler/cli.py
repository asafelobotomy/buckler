"""buckler Click entrypoint."""

from __future__ import annotations

from pathlib import Path

import click

from buckler import cli_device, cli_launcher  # registers command groups on `cli`
from buckler.adb import AdbError
from buckler.cli_common import cli, make_adb
from buckler.journal import latest_journal, seed_privacy_core_journal
from buckler.ops import (
    apply_profile,
    audit,
    check_drift,
    format_probe,
    probe,
    restore_all_journals,
    restore_journal,
)
from buckler.policy import verify_ai_stack
from buckler.profiles import list_profiles


@cli.command("probe")
@click.pass_context
def probe_cmd(ctx: click.Context) -> None:
    """Show device identity, HOME, disabled packages, AI stack health."""
    try:
        result = probe(make_adb(ctx.obj["host"]))
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(format_probe(result))


@cli.command("audit")
@click.pass_context
def audit_cmd(ctx: click.Context) -> None:
    """Classify profile packages as ENABLED / DISABLED / MISSING."""
    try:
        rows = audit(make_adb(ctx.obj["host"]))
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"{'PROFILE':<16} {'STATE':<10} PACKAGE")
    for row in rows:
        click.echo(f"{row.profile:<16} {row.state:<10} {row.package}")


@cli.command("apply")
@click.option(
    "--profile",
    "profile_name",
    required=True,
    type=click.Choice(
        [p for p in list_profiles() if p != "home-clean"],
        case_sensitive=True,
    ),
    help="Profile to apply (privacy-core, speech, minimal, extras, …).",
)
@click.option("--dry-run", is_flag=True, help="Show what would change; write no journal.")
@click.pass_context
def apply_cmd(ctx: click.Context, profile_name: str, dry_run: bool) -> None:
    """Disable packages in a profile with journaled restore."""
    if profile_name == "home-clean":
        raise click.ClickException("Use: buckler launcher handover")
    try:
        result = apply_profile(make_adb(ctx.obj["host"]), profile_name, dry_run=dry_run)
    except (AdbError, ValueError) as exc:
        raise click.ClickException(str(exc)) from exc

    prefix = "[dry-run] " if dry_run else ""
    click.echo(f"{prefix}changed ({len(result.changed)}):")
    for pkg in result.changed:
        click.echo(f"  + disabled {pkg}")
    if result.skipped_already:
        click.echo(f"already disabled ({len(result.skipped_already)}):")
        for pkg in result.skipped_already:
            click.echo(f"  = {pkg}")
    if result.skipped_missing:
        click.echo(f"missing ({len(result.skipped_missing)}):")
        for pkg in result.skipped_missing:
            click.echo(f"  ? {pkg}")
    if result.blocked:
        click.echo(f"blocked ({len(result.blocked)}):")
        for pkg in result.blocked:
            click.echo(f"  x {pkg}")
    if result.journal.path:
        click.echo(f"journal: {result.journal.path}")

    problems = verify_ai_stack(make_adb(ctx.obj["host"]))
    if problems:
        raise click.ClickException("AI stack degraded: " + "; ".join(problems))
    click.echo("AI stack: OK")


@cli.command("restore")
@click.option(
    "--journal",
    "journal_path",
    type=click.Path(path_type=Path, exists=True),
    default=None,
    help="Journal JSON to restore (default: latest disable journal).",
)
@click.option(
    "--all",
    "restore_all",
    is_flag=True,
    help="Re-enable packages from every disable journal (preferred after setup).",
)
@click.pass_context
def restore_cmd(ctx: click.Context, journal_path: Path | None, restore_all: bool) -> None:
    """Re-enable packages recorded in journal(s)."""
    if restore_all and journal_path is not None:
        raise click.ClickException("Use either --all or --journal, not both")
    try:
        if restore_all:
            results = restore_all_journals(make_adb(ctx.obj["host"]))
            if not results:
                raise click.ClickException("No journals found under journals/")
            for path, restored in results:
                click.echo(f"restored from {path}:")
                for pkg in restored:
                    click.echo(f"  + enabled {pkg}")
                if not restored:
                    click.echo("  (nothing to enable)")
            return
        path = journal_path or latest_journal()
        if path is None:
            raise click.ClickException("No journals found under journals/")
        restored = restore_journal(make_adb(ctx.obj["host"]), path)
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"restored from {path}:")
    for pkg in restored:
        click.echo(f"  + enabled {pkg}")
    if not restored:
        click.echo("  (nothing to enable)")


@cli.command("check")
@click.option(
    "--profile",
    "profile_name",
    default=None,
    help="Also want this profile disabled (default: what journals show Buckler disabled, "
    "minus anything restored since).",
)
@click.option("--fix", "do_fix", is_flag=True, help="Re-disable drifted packages.")
@click.pass_context
def check_cmd(ctx: click.Context, profile_name: str | None, do_fix: bool) -> None:
    """Detect OTA / drift vs desired disabled set; optionally re-apply."""
    try:
        drifted, ai = check_drift(
            make_adb(ctx.obj["host"]),
            profile_name=profile_name,
            fix=do_fix,
        )
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    if drifted:
        verb = "re-disabled" if do_fix else "should be disabled"
        click.echo(f"drift ({len(drifted)}) — {verb}:")
        for pkg in drifted:
            click.echo(f"  ! {pkg}")
    else:
        click.echo("drift: none")
    if ai:
        click.echo("AI stack PROBLEMS:")
        for item in ai:
            click.echo(f"  ! {item}")
        raise SystemExit(2)
    click.echo("AI stack: OK")


@cli.command("seed-journal")
@click.pass_context
def seed_journal_cmd(ctx: click.Context) -> None:
    """Write a journal for privacy-core disables applied before the CLI."""
    path = seed_privacy_core_journal(ctx.obj["host"])
    click.echo(f"wrote {path}")


@cli.command("profiles")
def profiles_cmd() -> None:
    """List applyable profiles."""
    for name in list_profiles():
        click.echo(name)


__all__ = ["cli", "cli_device", "cli_launcher"]

if __name__ == "__main__":
    cli()
