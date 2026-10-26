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
