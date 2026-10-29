"""Shared click group and ADB factory."""

from __future__ import annotations

import os

import click

from buckler import __version__
from buckler.adb import Adb

DEFAULT_HOST = "192.168.178.23"


def make_adb(host: str) -> Adb:
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
    default=DEFAULT_HOST,
    show_default=True,
    help="Shield IP or host:port (Network debugging). Env: BUCKLER_HOST or SHIELD_HOST.",
)
@click.pass_context
def cli(ctx: click.Context, host: str) -> None:
    """Buckler — ADB cleanup + launcher tooling for NVIDIA SHIELD TV Pro."""
    ctx.ensure_object(dict)
    ctx.obj["host"] = host
