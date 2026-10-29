"""Where Buckler's files live, whether run from a source checkout or installed with pip.

Read-only data (profiles, catalog, schemas) ships inside the package. Journals and the APK
cache stay in the checkout when there is one, so existing journals keep working; an installed
CLI keeps them in the user's state and cache folders instead.
"""

from __future__ import annotations

import os
from pathlib import Path

DATA = Path(__file__).resolve().parent / "data"


def checkout_root() -> Path | None:
    """The source checkout this package runs from (it has launcher/), or None when installed."""
    candidate = Path(__file__).resolve().parents[2]
    return candidate if (candidate / "launcher" / "gradlew").is_file() else None


def profiles_dir() -> Path:
    return DATA / "profiles"


def catalog_dir() -> Path:
    return DATA / "catalog"


def schema_dir() -> Path:
    return DATA / "schema"


def _user_dir(env: str, fallback: str) -> Path:
    base = os.environ.get(env)
    return (Path(base) if base else Path.home() / fallback) / "buckler"


def journals_dir() -> Path:
    root = checkout_root()
    path = root / "journals" if root else _user_dir("XDG_STATE_HOME", ".local/state") / "journals"
    path.mkdir(parents=True, exist_ok=True)
    return path


def launcher_dir() -> Path | None:
    """The launcher's Gradle project, present only in a source checkout."""
    root = checkout_root()
    return root / "launcher" if root else None


def apk_cache_dir() -> Path:
    root = checkout_root()
    path = root / ".cache" / "apks" if root else _user_dir("XDG_CACHE_HOME", ".cache") / "apks"
    path.mkdir(parents=True, exist_ok=True)
    return path
