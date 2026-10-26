"""Shared click group and ADB factory."""

from __future__ import annotations

import click

from buckler import __version__
from buckler.adb import Adb

DEFAULT_HOST = "192.168.178.23"


def make_adb(host: str) -> Adb:
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
