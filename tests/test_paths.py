from pathlib import Path

import pytest
from click.testing import CliRunner

from buckler import paths
from buckler.cli import cli
from buckler.cli_common import saved_host


def test_an_installed_cli_keeps_its_files_in_the_user_folders(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setattr(paths, "checkout_root", lambda: None)
    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path / "state"))
    monkeypatch.setenv("XDG_CACHE_HOME", str(tmp_path / "cache"))
    assert paths.apk_cache_dir() == tmp_path / "cache" / "buckler" / "apks"
    assert paths.launcher_dir() is None
    assert (paths.profiles_dir() / "never.yaml").is_file()


def test_the_env_host_beats_the_remembered_one(monkeypatch: pytest.MonkeyPatch) -> None:
    CliRunner().invoke(cli, ["--host", "10.0.0.5", "profiles"])
    assert saved_host() == "10.0.0.5"
    seen: list[str] = []
    monkeypatch.setattr("buckler.cli_common.Adb", lambda host: seen.append(host) or (_ for _ in ()).throw(SystemExit))
    CliRunner().invoke(cli, ["probe"], env={"BUCKLER_HOST": "10.0.0.9"})
    assert seen == ["10.0.0.9"]
