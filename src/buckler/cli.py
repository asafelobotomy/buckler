"""buckler Click entrypoint."""

from __future__ import annotations

import json
from pathlib import Path

import click

from buckler import __version__
from buckler.adb import Adb, AdbError
from buckler.home_layout import (
    HomeLayoutError,
    default_home_layout,
    default_layout_path,
    push_home_layout,
    validate_home_layout,
    write_home_layout,
)
from buckler.journal import latest_journal, seed_privacy_core_journal
from buckler.launcher_ops import (
    build_launcher,
    handover,
    home_is_ours,
    install_launcher,
    verify_launcher_installed,
)
from buckler.ops import (
    apply_profile,
    audit,
    check_drift,
    format_probe,
    probe,
    restore_all_journals,
    restore_journal,
)
from buckler.policy import OUR_LAUNCHER, verify_ai_stack
from buckler.profiles import SETUP_PROFILES, list_profiles
from buckler.setup_wizard import export_snapshot, run_restore_wizard, run_setup
from buckler.tune import (
    clear_private_dns,
    compile_launcher_speed,
    doctor,
    set_animation_scales,
    set_our_screensaver,
    set_private_dns,
)
from buckler.tune import disable_backdrop as replace_backdrop_with_clock

DEFAULT_HOST = "192.168.178.23"


def _adb(host: str) -> Adb:
    return Adb(host=host)


@click.group()
@click.version_option(__version__, prog_name="buckler")
@click.option(
    "--host",
    envvar=["BUCKLER_HOST", "SHIELD_HOST"],
    default=DEFAULT_HOST,
    show_default=True,
    help="Shield IP or host:port (Network debugging). Env: BUCKLER_HOST or SHIELD_HOST.",
)
@click.pass_context
def cli(ctx: click.Context, host: str) -> None:
    """Buckler — ADB cleanup + launcher tooling for NVIDIA SHIELD TV Pro."""
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
    help="Profile to apply (privacy-core, speech, minimal, extras, …).",
)
@click.option("--dry-run", is_flag=True, help="Show what would change; write no journal.")
@click.pass_context
def apply_cmd(ctx: click.Context, profile_name: str, dry_run: bool) -> None:
    """Disable packages in a profile with journaled restore."""
    if profile_name == "home-clean":
        raise click.ClickException("Use: buckler launcher handover")
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
            results = restore_all_journals(_adb(ctx.obj["host"]))
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
        restored = restore_journal(_adb(ctx.obj["host"]), path)
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
    help="Limit desired set to one profile (default: setup profiles "
    + ", ".join(SETUP_PROFILES)
    + ").",
)
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
    """Build / install / verify / handover buckler-home."""


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
    """Check package install and whether HOME points at buckler-home."""
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
    """Install launcher if needed, disable stock HOME(s), verify."""
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


@cli.group("home")
def home_group() -> None:
    """buckler-home layout defaults (home-layout.v1)."""


@home_group.command("show")
@click.option(
    "--file",
    "layout_path",
    type=click.Path(path_type=Path),
    default=None,
    help="Layout JSON (default: journals/home-layout.json or built-in default).",
)
def home_show(layout_path: Path | None) -> None:
    """Print home-layout.v1 JSON."""
    path = layout_path or default_layout_path()
    if path.is_file():
        doc = json.loads(path.read_text(encoding="utf-8"))
    else:
        doc = default_home_layout()
    try:
        validate_home_layout(doc)
    except HomeLayoutError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(json.dumps(doc, indent=2))


@home_group.command("write")
@click.option(
    "--out",
    type=click.Path(path_type=Path),
    default=None,
    help="Output path (default: journals/home-layout.json).",
)
@click.option(
    "--layout",
    type=click.Choice(["icon", "poster", "banner"], case_sensitive=True),
    default="banner",
    show_default=True,
)
def home_write(out: Path | None, layout: str) -> None:
    """Write default home-layout.v1 JSON."""
    path = write_home_layout(out, default_home_layout(layout=layout))
    click.echo(str(path))


@home_group.command("push")
@click.option(
    "--file",
    "layout_path",
    type=click.Path(path_type=Path, exists=True),
    default=None,
    help="Layout JSON to push (default: write journals/home-layout.json first).",
)
@click.pass_context
def home_push(ctx: click.Context, layout_path: Path | None) -> None:
    """Push home-layout.json onto the Shield for buckler-home to import."""
    path = layout_path
    if path is None:
        path = write_home_layout()
    try:
        remote = push_home_layout(_adb(ctx.obj["host"]), path)
    except (AdbError, HomeLayoutError) as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"pushed {path} → {remote}")
    click.echo("Open HOME (or resume buckler-home) to import.")


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
    help="Prompt before each mutating phase (privacy, speech, home, …).",
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
) -> None:
    """Multi-step setup wizard (stock path; phases shared with kitetvOS)."""

    def _ask(prompt: str) -> bool:
        return click.confirm(prompt, default=True)

    try:
        report = run_setup(
            _adb(ctx.obj["host"]),
            dry_run=dry_run,
            skip_build=skip_build,
            skip_home_layout=skip_home_layout,
            interactive=interactive,
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
            _adb(ctx.obj["host"]),
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
        path = export_snapshot(_adb(ctx.obj["host"]), out)
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(str(path))


@cli.command("doctor")
@click.pass_context
def doctor_cmd(ctx: click.Context) -> None:
    """Health check: HOME/AI/Chromecast hard-fail; screensaver is a warning."""
    try:
        report = doctor(_adb(ctx.obj["host"]))
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
    adb = _adb(ctx.obj["host"])
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
    """Set buckler-home ClockDream as screensaver; optionally disable Backdrop."""
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
