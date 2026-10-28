"""`buckler unused` lists (and optionally disables) apps Buckler found unused; `buckler clean`
clears every app's cache."""

from __future__ import annotations

import click

from buckler.adb import AdbError
from buckler.cli_common import cli, make_adb
from buckler.unused import clear_caches, disable_unused, read_report


@cli.command("unused")
@click.argument("packages", nargs=-1)
@click.option("--apply", "apply_", is_flag=True, help="Disable them (all listed, or PACKAGES).")
@click.option("--dry-run", is_flag=True, help="With --apply: show what would change.")
@click.pass_context
def unused_cmd(ctx: click.Context, packages: tuple[str, ...], apply_: bool, dry_run: bool) -> None:
    """Show apps not opened for a while (from Buckler Settings → Clean up → Apps); --apply disables them."""
    adb = make_adb(ctx.obj["host"])
    try:
        report = read_report(adb)
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"unused for {report.days}+ days (checked {report.generated_at[:16]}):")
    if not report.apps:
        click.echo("  none")
        return
    for app in report.apps:
        kind = "stock" if app.system else "yours"
        click.echo(f"  {app.label} ({app.package}, {kind}): {app.describe()}")
    if not apply_:
        click.echo("Disable with: buckler unused --apply [PACKAGES]  (buckler restore undoes it)")
        return
    if not dry_run:
        click.confirm("Disable these apps for user 0?", abort=True)
    try:
        result = disable_unused(adb, report, list(packages), dry_run=dry_run)
    except (AdbError, ValueError) as exc:
        raise click.ClickException(str(exc)) from exc
    prefix = "[dry-run] " if dry_run else ""
    for pkg in result.changed:
        click.echo(f"{prefix}  + disabled {pkg}")
    for pkg in result.blocked:
        click.echo(f"  x kept {pkg} (never list or HOME)")


@cli.command("clean")
@click.pass_context
def clean_cmd(ctx: click.Context) -> None:
    """Clear every app's cache on the TV (apps rebuild what they need; nothing else is removed)."""
    adb = make_adb(ctx.obj["host"])
    try:
        freed = clear_caches(adb)
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"cleared app caches: {freed / 1024:.0f} MB freed")
