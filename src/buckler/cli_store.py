"""`buckler store`: bucklerStore's widgets, screensavers, colour schemes, fonts and icon packs, from a computer."""

from __future__ import annotations

import click

from buckler.adb import AdbError
from buckler.cli_common import cli, make_adb
from buckler.store import KINDS, StoreError, find, install, installed, load_index, remove

_LIVE = click.option("--live", is_flag=True, help="Use the current catalog from GitHub, not the one Buckler ships.")


@cli.group("store")
def store_group() -> None:
    """bucklerStore: add widgets, screensavers, colour schemes, fonts and icon packs to buckler-home."""


@store_group.command("list")
@click.option("--kind", type=click.Choice(KINDS), help="Only one kind.")
@_LIVE
def store_list(kind: str | None, live: bool) -> None:
    """What the store offers."""
    try:
        items = load_index(live=live)
    except StoreError as exc:
        raise click.ClickException(str(exc)) from exc
    for group in KINDS:
        if kind and group != kind:
            continue
        click.echo(f"{group}:")
        for item in (i for i in items if i.kind == group):
            click.echo(f"  {item.id:<16} {item.label} — {item.about}")


@store_group.command("install")
@click.argument("ids", nargs=-1, required=True)
@_LIVE
@click.pass_context
def store_install(ctx: click.Context, ids: tuple[str, ...], live: bool) -> None:
    """Install one or more items by id (Buckler picks them up when its Settings next opens)."""
    _each(ctx, ids, live, install)


@store_group.command("remove")
@click.argument("ids", nargs=-1, required=True)
@_LIVE
@click.pass_context
def store_remove(ctx: click.Context, ids: tuple[str, ...], live: bool) -> None:
    """Remove items installed from the store (a widget or screensaver: take it off Buckler's choices)."""
    _each(ctx, ids, live, remove)


@store_group.command("installed")
@click.pass_context
def store_installed(ctx: click.Context) -> None:
    """What the TV has from the store."""
    try:
        state = installed(make_adb(ctx.obj["host"]))
    except AdbError as exc:
        raise click.ClickException(str(exc)) from exc
    for kind, names in state.items():
        click.echo(f"{kind}: {', '.join(names) if names else 'none'}")


def _each(ctx: click.Context, ids: tuple[str, ...], live: bool, action) -> None:
    try:
        items = load_index(live=live)
        chosen = [find(items, ident) for ident in ids]
        adb = make_adb(ctx.obj["host"])
        for item in chosen:
            click.echo(action(adb, item))
    except (StoreError, AdbError) as exc:
        raise click.ClickException(str(exc)) from exc
