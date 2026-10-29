"""Shared click group and ADB factory."""

from __future__ import annotations

import os
from pathlib import Path

import click

from buckler import __version__
from buckler.adb import Adb


def host_file() -> Path:
    base = os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config"
    return Path(base) / "buckler" / "host"


def saved_host() -> str | None:
    try:
        return host_file().read_text(encoding="utf-8").strip() or None
    except OSError:
        return None


def save_host(host: str) -> None:
    """Remembers the TV given with --host (or the env), so later commands need not repeat it."""
    try:
        path = host_file()
        path.parent.mkdir(parents=True, exist_ok=True)
        if saved_host() != host:
            path.write_text(host + "\n", encoding="utf-8")
            click.echo(f"(remembering {host} for next time: {path})", err=True)
    except OSError:
        pass


def make_adb(host: str | None) -> Adb:
    if not host:
        raise click.ClickException(
            "Which TV? Pass --host <tv-ip> once (it is remembered) or set BUCKLER_HOST. "
            "The TV's address is in Settings → Device Preferences → About → Status."
        )
    return Adb(host=host)


class BucklerGroup(click.Group):
    """
    Turns a failure the CLI can meet in normal use (adb missing or unreachable, a hand-edited
    journal, layout or catalog that does not parse, a file that cannot be read) into a one-line
    error rather than a traceback. `BUCKLER_DEBUG=1` shows the traceback instead.
    """

    def invoke(self, ctx: click.Context) -> object:
        try:
            return super().invoke(ctx)
        except (click.ClickException, click.exceptions.Exit, click.Abort):
            raise
        except Exception as exc:
            if os.environ.get("BUCKLER_DEBUG"):
                raise
            raise click.ClickException(f"{type(exc).__name__}: {exc}") from exc


@click.group(cls=BucklerGroup)
@click.version_option(__version__, prog_name="buckler")
@click.option(
    "--host",
    envvar=["BUCKLER_HOST", "SHIELD_HOST"],
    default=None,
    help="TV IP or host:port (Network debugging); remembered for next time. "
    "Env: BUCKLER_HOST or SHIELD_HOST.",
)
@click.pass_context
def cli(ctx: click.Context, host: str | None) -> None:
    """Buckler — ADB cleanup + launcher tooling for NVIDIA SHIELD TV Pro."""
    ctx.ensure_object(dict)
    if host:
        save_host(host)
    ctx.obj["host"] = host or saved_host()
