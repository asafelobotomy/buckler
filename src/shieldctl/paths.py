"""Resolve repo-relative paths when installed editable or run from source."""

from __future__ import annotations

from pathlib import Path


def repo_root() -> Path:
    """Return the shieldctl repository root (contains profiles/, launcher/)."""
    here = Path(__file__).resolve()
    # src/shieldctl/paths.py -> repo root
    candidate = here.parents[2]
    if (candidate / "profiles").is_dir():
        return candidate
    # Fallback: cwd
    cwd = Path.cwd()
    if (cwd / "profiles").is_dir():
        return cwd
    raise RuntimeError("Cannot locate shieldctl repo root (profiles/ missing)")


def profiles_dir() -> Path:
    return repo_root() / "profiles"


def journals_dir() -> Path:
    path = repo_root() / "journals"
    path.mkdir(parents=True, exist_ok=True)
    return path


def launcher_dir() -> Path:
    return repo_root() / "launcher"
