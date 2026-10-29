from click.testing import CliRunner

from buckler.cli import cli
from buckler.cli_common import saved_host


def test_without_a_host_the_cli_asks_for_one() -> None:
    result = CliRunner().invoke(cli, ["probe"])
    assert result.exit_code != 0
    assert "--host" in result.output


def test_a_given_host_is_remembered() -> None:
    CliRunner().invoke(cli, ["--host", "10.0.0.5", "profiles"])
    assert saved_host() == "10.0.0.5"
