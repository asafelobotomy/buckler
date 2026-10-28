"""Setup wizard, tuning, DNS and screensaver commands."""

from __future__ import annotations

from pathlib import Path

import click

from buckler.adb import AdbError
from buckler.app_catalog import CatalogError, load_catalog
from buckler.cli_common import cli, make_adb
from buckler.policy import OUR_LAUNCHER
from buckler.setup_wizard import WizardReport, export_snapshot, run_setup
from buckler.tune import (
    clear_private_dns,
    compile_launcher_speed,
    doctor,
    get_private_dns,
    set_animation_scales,
    set_our_screensaver,
    set_private_dns,
)
from buckler.tune import disable_backdrop as replace_backdrop_with_clock
from buckler.wizard_apps import run_app_phases
from buckler.wizard_restore import run_restore_wizard


@cli.command("setup")
@click.option("--dry-run", is_flag=True, help="Apply profile dry-runs only; skip launcher.")
@click.option(
    "--skip-build",
    is_flag=True,
    help="Reuse an already-built APK (still installs + hands over HOME).",
)
@click.option(
    "--skip-home-layout",
    is_flag=True,
    help="Do not write/push home-layout.v1 after launcher handover.",
)
@click.option(
    "--interactive/--batch",
    default=False,
    help="Prompt before each mutating phase. App swaps and suggestions default to keep.",
)
@click.option(
    "--swap",
    "swaps",
    multiple=True,
    help="Alternative id to pin (repeatable). Batch setup keeps stock unless this is set.",
)
@click.option(
    "--suggest",
    "suggestions",
    multiple=True,
    help="Suggested app id to install (repeatable). Batch setup installs none unless set.",
)
@click.confirmation_option(
    prompt="Run multi-step buckler setup on this Shield?",
)
@click.pass_context
def setup_cmd(
    ctx: click.Context,
    dry_run: bool,
    skip_build: bool,
    skip_home_layout: bool,
    interactive: bool,
    swaps: tuple[str, ...],
    suggestions: tuple[str, ...],
) -> None:
    """Multi-step setup wizard (stock path; phases shared with kitetvOS)."""

    def _ask(prompt: str, default: bool = True) -> bool:
        return click.confirm(prompt, default=default)

    try:
        report = run_setup(
            make_adb(ctx.obj["host"]),
            dry_run=dry_run,
            skip_build=skip_build,
            skip_home_layout=skip_home_layout,
            interactive=interactive,
            swaps=swaps,
            suggestions=suggestions,
            echo=click.echo,
            ask=_ask if interactive else None,
        )
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo("")
    click.echo(f"setup ok={report.ok} runtime={report.runtime}")
    for step in report.steps:
        if step.skipped:
            mark = "skip"
        elif step.ok:
            mark = "ok"
        else:
            mark = "FAIL"
        click.echo(f"  [{mark}] {step.name}: {step.detail}")
    if report.snapshot_path:
        click.echo(f"snapshot: {report.snapshot_path}")
    if not report.ok:
        raise SystemExit(1)


@cli.command("restore-wizard")
@click.option(
    "--journal",
    "journal_path",
    type=click.Path(path_type=Path, exists=True),
    default=None,
    help="Journal to re-enable (default: latest, unless --all).",
)
@click.option(
    "--all",
    "restore_all",
    is_flag=True,
    help="Re-enable from every disable journal (preferred after setup).",
)
@click.confirmation_option(prompt="Re-enable packages from journal(s) on this Shield?")
@click.pass_context
def restore_wizard_cmd(
    ctx: click.Context,
    journal_path: Path | None,
    restore_all: bool,
) -> None:
    """Guided restore from journal(s), then doctor."""
    if restore_all and journal_path is not None:
        raise click.ClickException("Use either --all or --journal, not both")
    try:
        report = run_restore_wizard(
            make_adb(ctx.obj["host"]),
            journal=journal_path,
            all_journals=restore_all,
            echo=click.echo,
        )
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"restore-wizard ok={report.ok}")
    if not report.ok:
        raise SystemExit(1)


@cli.command("snapshot")
@click.option(
    "--out",
    type=click.Path(path_type=Path),
    default=None,
    help="Output path (default: journals/snapshot-*.json).",
)
@click.pass_context
def snapshot_cmd(ctx: click.Context, out: Path | None) -> None:
    """Export desired-state snapshot JSON for this Shield."""
    try:
        path = export_snapshot(make_adb(ctx.obj["host"]), out)
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(str(path))


@cli.command("doctor")
@click.pass_context
def doctor_cmd(ctx: click.Context) -> None:
    """Health check: HOME/AI/Chromecast hard-fail; screensaver is a warning."""
    try:
        report = doctor(make_adb(ctx.obj["host"]))
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    for line in report.lines:
        click.echo(line)
    if report.warnings and report.ok:
        click.echo(f"(warnings: {len(report.warnings)} — exit 0)")
    if not report.ok:
        raise SystemExit(1)


@cli.command("tune")
@click.option("--anim", default=0.5, show_default=True, type=float, help="Animation scale.")
@click.option("--compile/--no-compile", default=True, help="Speed-compile buckler-home.")
@click.pass_context
def tune_cmd(ctx: click.Context, anim: float, compile: bool) -> None:
    """Snappier animations and optional speed-profile compile."""
    adb = make_adb(ctx.obj["host"])
    try:
        set_animation_scales(adb, anim)
        click.echo(f"animation scales: {anim}")
        if compile:
            out = compile_launcher_speed(adb)
            click.echo(out or "compiled buckler-home (speed)")
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc


@cli.group("dns")
def dns_group() -> None:
    """Private DNS (DoT) helpers — pick your own hostname."""


def _dns_status_echo(ctx: click.Context) -> None:
    mode, spec = get_private_dns(make_adb(ctx.obj["host"]))
    click.echo(f"private_dns_mode={mode}")
    click.echo(f"private_dns_specifier={spec}")


@dns_group.command("status")
@click.pass_context
def dns_status(ctx: click.Context) -> None:
    """Show Private DNS mode and hostname specifier."""
    try:
        _dns_status_echo(ctx)
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc


@dns_group.command("get")
@click.pass_context
def dns_get(ctx: click.Context) -> None:
    """Alias for dns status."""
    try:
        _dns_status_echo(ctx)
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc


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
        set_private_dns(make_adb(ctx.obj["host"]), hostname)
    except (AdbError, ValueError) as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"private_dns_mode=hostname specifier={hostname}")


@dns_group.command("off")
@click.pass_context
def dns_off(ctx: click.Context) -> None:
    """Disable Private DNS."""
    try:
        clear_private_dns(make_adb(ctx.obj["host"]))
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo("private_dns_mode=off")


def _print_report(report: WizardReport) -> None:
    for step in report.steps:
        if step.skipped:
            mark = "skip"
        elif step.ok:
            mark = "ok"
        else:
            mark = "FAIL"
        click.echo(f"  [{mark}] {step.name}: {step.detail}")


@cli.group("apps")
def apps_group() -> None:
    """Open alternatives and optional suggested apps."""


@apps_group.command("list")
def apps_list() -> None:
    """Show alternative and suggestion ids."""
    try:
        catalog = load_catalog()
    except CatalogError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo("alternatives (default: keep stock):")
    for entry in catalog.swaps:
        click.echo(f"  {entry.id}: {entry.label} — {entry.note}")
    click.echo("suggestions (default: skip):")
    for entry in catalog.suggestions:
        click.echo(f"  {entry.id}: {entry.label} — {entry.note}")


@apps_group.command("apply")
@click.option("--swap", "swaps", multiple=True, help="Alternative id to pin (repeatable).")
@click.option("--suggest", "suggestions", multiple=True, help="Suggested app id to install.")
@click.option("--dry-run", is_flag=True, help="Print choices only. Download nothing.")
@click.option(
    "--interactive/--batch",
    default=False,
    help="Prompt for every catalog app not already named. Default answer is no.",
)
@click.pass_context
def apps_apply(
    ctx: click.Context,
    swaps: tuple[str, ...],
    suggestions: tuple[str, ...],
    dry_run: bool,
    interactive: bool,
) -> None:
    """Install chosen apps and pin them. Does not disable stock packages."""

    def _ask(prompt: str, default: bool = False) -> bool:
        return click.confirm(prompt, default=default)

    adb = make_adb(ctx.obj["host"])
    report = WizardReport(host=adb.host, mode="apps", runtime="stock-adb")
    try:
        adb.connect()
        push = (not dry_run) and adb.package_present(OUR_LAUNCHER)
        run_app_phases(
            adb,
            report,
            swaps=swaps,
            suggestions=suggestions,
            dry_run=dry_run,
            ask=_ask if interactive else None,
            echo=click.echo,
            push_layout=push,
        )
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"apps ok={report.ok}")
    _print_report(report)
    if not report.ok:
        raise SystemExit(1)


@cli.command("screensaver")
@click.option("--disable-backdrop/--keep-backdrop", default=True)
@click.pass_context
def screensaver_cmd(ctx: click.Context, disable_backdrop: bool) -> None:
    """Set buckler-home ClockDream as screensaver; optionally disable Backdrop."""
    adb = make_adb(ctx.obj["host"])
    try:
        if disable_backdrop:
            journal = replace_backdrop_with_clock(adb)
            click.echo(f"screensaver set; backdrop disabled; journal={journal.path}")
        else:
            set_our_screensaver(adb)
            click.echo("screensaver set; backdrop kept")
    except (AdbError, RuntimeError) as exc:
        raise click.ClickException(str(exc)) from exc
