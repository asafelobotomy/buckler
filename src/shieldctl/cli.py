"""shieldctl Click entrypoint."""

from __future__ import annotations

from pathlib import Path

import click

from shieldctl import __version__
from shieldctl.adb import Adb, AdbError
from shieldctl.journal import latest_journal, seed_privacy_core_journal
from shieldctl.launcher_ops import (
    build_launcher,
    handover,
    home_is_ours,
    install_launcher,
    verify_launcher_installed,
)
from shieldctl.ops import apply_profile, audit, check_drift, format_probe, probe, restore_journal
from shieldctl.policy import OUR_LAUNCHER, verify_ai_stack
from shieldctl.profiles import list_profiles
from shieldctl.tune import (
    clear_private_dns,
    compile_launcher_speed,
    disable_backdrop as replace_backdrop_with_clock,
    doctor,
    set_animation_scales,
    set_our_screensaver,
    set_private_dns,
)


DEFAULT_HOST = "192.168.178.23"


def _adb(host: str) -> Adb:
    return Adb(host=host)


@click.group()
@click.version_option(__version__, prog_name="shieldctl")
@click.option(
    "--host",
    envvar="SHIELD_HOST",
    default=DEFAULT_HOST,
    show_default=True,
    help="Shield IP or host:port (Network debugging).",
)
@click.pass_context
def cli(ctx: click.Context, host: str) -> None:
    """ADB cleanup + launcher tooling for NVIDIA SHIELD TV Pro."""
    ctx.ensure_object(dict)
    ctx.obj["host"] = host


@cli.command("probe")
@click.pass_context
def probe_cmd(ctx: click.Context) -> None:
    """Show device identity, HOME, disabled packages, AI stack health."""
    try:
        result = probe(_adb(ctx.obj["host"]))
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(format_probe(result))


@cli.command("audit")
@click.pass_context
def audit_cmd(ctx: click.Context) -> None:
    """Classify profile packages as ENABLED / DISABLED / MISSING."""
    try:
        rows = audit(_adb(ctx.obj["host"]))
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
    help="Profile to apply (privacy-core, minimal).",
)
@click.option("--dry-run", is_flag=True, help="Show what would change; write no journal.")
@click.pass_context
def apply_cmd(ctx: click.Context, profile_name: str, dry_run: bool) -> None:
    """Disable packages in a profile with journaled restore."""
    if profile_name == "home-clean":
        raise click.ClickException("Use: shieldctl launcher handover")
    try:
        result = apply_profile(_adb(ctx.obj["host"]), profile_name, dry_run=dry_run)
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

    problems = verify_ai_stack(_adb(ctx.obj["host"]))
    if problems:
        raise click.ClickException("AI stack degraded: " + "; ".join(problems))
    click.echo("AI stack: OK")


@cli.command("restore")
@click.option(
    "--journal",
    "journal_path",
    type=click.Path(path_type=Path, exists=True),
    default=None,
    help="Journal JSON to restore (default: latest).",
)
@click.pass_context
def restore_cmd(ctx: click.Context, journal_path: Path | None) -> None:
    """Re-enable packages recorded in a journal."""
    path = journal_path or latest_journal()
    if path is None:
        raise click.ClickException("No journals found under journals/")
    try:
        restored = restore_journal(_adb(ctx.obj["host"]), path)
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"restored from {path}:")
    for pkg in restored:
        click.echo(f"  + enabled {pkg}")
    if not restored:
        click.echo("  (nothing to enable)")


@cli.command("check")
@click.option("--profile", "profile_name", default=None, help="Limit desired set to one profile.")
@click.option("--fix", "do_fix", is_flag=True, help="Re-disable drifted packages.")
@click.pass_context
def check_cmd(ctx: click.Context, profile_name: str | None, do_fix: bool) -> None:
    """Detect OTA / drift vs desired disabled set; optionally re-apply."""
    try:
        drifted, ai = check_drift(
            _adb(ctx.obj["host"]),
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


@cli.group("launcher")
def launcher_group() -> None:
    """Build / install / verify / handover shieldhome."""


@launcher_group.command("build")
def launcher_build() -> None:
    """Assemble the release APK with Gradle."""
    try:
        result = build_launcher()
    except RuntimeError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"apk: {result.apk}")


@launcher_group.command("install")
@click.pass_context
def launcher_install(ctx: click.Context) -> None:
    """Build if needed and install APK (does not change HOME)."""
    try:
        out = install_launcher(_adb(ctx.obj["host"]))
    except (AdbError, RuntimeError) as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(out)
    click.echo(f"installed={verify_launcher_installed(_adb(ctx.obj['host']))}")


@launcher_group.command("verify")
@click.pass_context
def launcher_verify(ctx: click.Context) -> None:
    """Check package install and whether HOME points at shieldhome."""
    adb = _adb(ctx.obj["host"])
    try:
        adb.connect()
        installed = verify_launcher_installed(adb)
        ours = home_is_ours(adb)
        home = adb.resolve_home()
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"package {OUR_LAUNCHER}: {'installed' if installed else 'MISSING'}")
    click.echo(f"home: {home}")
    click.echo(f"ours: {ours}")
    if not installed or not ours:
        raise SystemExit(1)


@launcher_group.command("handover")
@click.option("--reboot", is_flag=True, help="Reboot Shield after successful handover.")
@click.pass_context
def launcher_handover(ctx: click.Context, reboot: bool) -> None:
    """Install launcher if needed, disable stock HOME, verify."""
    try:
        result = handover(_adb(ctx.obj["host"]), reboot=reboot)
    except (AdbError, RuntimeError) as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"before: {result.home_before}")
    click.echo(f"after:  {result.home_after}")
    click.echo(result.message)
    if result.journal_path:
        click.echo(f"journal: {result.journal_path}")
    if not result.success:
        raise SystemExit(1)


@cli.command("profiles")
def profiles_cmd() -> None:
    """List applyable profiles."""
    for name in list_profiles():
        click.echo(name)


@cli.command("doctor")
@click.pass_context
def doctor_cmd(ctx: click.Context) -> None:
    """Health check: HOME, AI stack, sticky disables, screensaver, reminders."""
    try:
        report = doctor(_adb(ctx.obj["host"]))
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    for line in report.lines:
        click.echo(line)
    if not report.ok:
        raise SystemExit(1)


@cli.command("tune")
@click.option("--anim", default=0.5, show_default=True, type=float, help="Animation scale.")
@click.option("--compile/--no-compile", default=True, help="Speed-compile shieldhome.")
@click.pass_context
def tune_cmd(ctx: click.Context, anim: float, compile: bool) -> None:
    """Snappier animations and optional speed-profile compile."""
    adb = _adb(ctx.obj["host"])
    try:
        set_animation_scales(adb, anim)
        click.echo(f"animation scales: {anim}")
        if compile:
            out = compile_launcher_speed(adb)
            click.echo(out or "compiled shieldhome (speed)")
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc


@cli.group("dns")
def dns_group() -> None:
    """Private DNS (DoT) helpers — pick your own hostname."""


@dns_group.command("set")
@click.option(
    "--hostname",
    required=True,
    help="DoT hostname, e.g. dns.nextdns.io or family.cloudflare-dns.com",
)
@click.pass_context
def dns_set(ctx: click.Context, hostname: str) -> None:
    """Enable Private DNS hostname mode."""
    try:
        set_private_dns(_adb(ctx.obj["host"]), hostname)
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"private_dns_mode=hostname specifier={hostname}")


@dns_group.command("off")
@click.pass_context
def dns_off(ctx: click.Context) -> None:
    """Disable Private DNS."""
    try:
        clear_private_dns(_adb(ctx.obj["host"]))
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo("private_dns_mode=off")


@cli.command("screensaver")
@click.option("--disable-backdrop/--keep-backdrop", default=True)
@click.pass_context
def screensaver_cmd(ctx: click.Context, disable_backdrop: bool) -> None:
    """Set shieldhome ClockDream as screensaver; optionally disable Backdrop."""
    adb = _adb(ctx.obj["host"])
    try:
        if disable_backdrop:
            journal = replace_backdrop_with_clock(adb)
            click.echo(f"screensaver set; backdrop disabled; journal={journal.path}")
        else:
            set_our_screensaver(adb)
            click.echo("screensaver set; backdrop kept")
    except (AdbError, RuntimeError) as exc:
        raise click.ClickException(str(exc)) from exc


if __name__ == "__main__":
    cli()
