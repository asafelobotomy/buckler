"""Pytest fixtures."""

from __future__ import annotations

from pathlib import Path

import pytest


@pytest.fixture()
def journals_dir(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    jdir = tmp_path / "journals"
    jdir.mkdir()
    monkeypatch.setattr("buckler.paths.journals_dir", lambda: jdir)
    monkeypatch.setattr("buckler.journal.journals_dir", lambda: jdir)
    monkeypatch.setattr("buckler.launcher_ops.journals_dir", lambda: jdir)
    monkeypatch.setattr("buckler.tune.journals_dir", lambda: jdir)
    monkeypatch.setattr("buckler.setup_wizard.journals_dir", lambda: jdir)
    return jdir


@pytest.fixture(autouse=True)
def _own_config(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """Keeps the remembered --host of a test run out of the real ~/.config."""
    monkeypatch.setenv("XDG_CONFIG_HOME", str(tmp_path / "config"))
    monkeypatch.delenv("BUCKLER_HOST", raising=False)
    monkeypatch.delenv("SHIELD_HOST", raising=False)
