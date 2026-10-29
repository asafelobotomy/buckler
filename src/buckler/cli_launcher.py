"""Launcher build/install and home-layout commands."""

from __future__ import annotations

import json
from pathlib import Path

import click

from buckler.adb import AdbError
from buckler.app_catalog import CatalogError
from buckler.cli_common import cli, make_adb
from buckler.home_layout import (
    HomeLayoutError,
    default_home_layout,
    default_layout_path,
    push_home_layout,
    validate_home_layout,
    write_home_layout,
)
from buckler.iconpacks import install_iconpack, load_iconpacks, require
from buckler.launcher_ops import (
    build_launcher,
    grant_media_access,
    grant_settings_access,
    handover,
    home_is_ours,
    install_launcher,
    push_screensaver_photos,
    verify_launcher_installed,
)
from buckler.policy import OUR_LAUNCHER


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
        out = install_launcher(make_adb(ctx.obj["host"]))
    except (AdbError, RuntimeError) as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(out)
    click.echo(f"installed={verify_launcher_installed(make_adb(ctx.obj['host']))}")


@launcher_group.group("iconpack")
def iconpack_group() -> None:
    """Open icon packs for Settings → Appearance → App Thumbnails."""


@iconpack_group.command("list")
def iconpack_list() -> None:
    """Show the icon packs Buckler can install."""
    for pack in load_iconpacks():
        click.echo(f"  {pack.id}: {pack.label} {pack.version} ({pack.license}). {pack.why}")


@iconpack_group.command("install")
@click.argument("pack_id")
@click.pass_context
def iconpack_install(ctx: click.Context, pack_id: str) -> None:
    """Download PACK_ID (checked against its pinned sha256) and install it on the TV."""
    try:
        pack = require(load_iconpacks(), pack_id)
        install_iconpack(make_adb(ctx.obj["host"]), pack)
    except (AdbError, CatalogError) as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"installed {pack.label}; choose it in Settings → Appearance → App Thumbnails")


@launcher_group.command("photos")
@click.argument("files", nargs=-1, required=True, type=click.Path(path_type=Path))
@click.pass_context
def launcher_photos(ctx: click.Context, files: tuple[Path, ...]) -> None:
    """Push jpg/png/webp files for the "My photos" screensaver."""
    try:
        pushed = push_screensaver_photos(make_adb(ctx.obj["host"]), list(files))
    except (AdbError, ValueError) as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"pushed {len(pushed)} photo(s) to {pushed[0].rsplit('/', 1)[0]}")


@launcher_group.command("media-access")
@click.pass_context
def launcher_media_access(ctx: click.Context) -> None:
    """Grant the header Now Playing panel access to media sessions."""
    try:
        click.echo(grant_media_access(make_adb(ctx.obj["host"])) or "granted")
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc


@launcher_group.command("grant-settings")
@click.pass_context
def launcher_grant_settings(ctx: click.Context) -> None:
    """One-time grants: screensaver and audio settings, self-updates, and app usage (Settings → Clean up)."""
    try:
        for command in grant_settings_access(make_adb(ctx.obj["host"])):
            click.echo(f"ok: {command}")
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc


@launcher_group.command("verify")
@click.pass_context
def launcher_verify(ctx: click.Context) -> None:
    """Check package install and whether HOME points at buckler-home."""
    adb = make_adb(ctx.obj["host"])
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
        result = handover(make_adb(ctx.obj["host"]), reboot=reboot)
    except (AdbError, RuntimeError) as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"before: {result.home_before}")
    click.echo(f"after:  {result.home_after}")
    click.echo(result.message)
    if result.journal_path:
        click.echo(f"journal: {result.journal_path}")
    if not result.success:
        raise SystemExit(1)


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
        remote = push_home_layout(make_adb(ctx.obj["host"]), path)
    except (AdbError, HomeLayoutError) as exc:
        raise click.ClickException(str(exc)) from exc
    click.echo(f"pushed {path} → {remote}")
    click.echo("Open HOME (or resume buckler-home) to import.")
