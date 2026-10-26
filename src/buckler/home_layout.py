"""home-layout.v1 defaults, validation, and device push helpers."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from buckler.adb import Adb
from buckler.paths import journals_dir, repo_root

DEFAULT_DOCK = [
    "org.jellyfin.androidtv",
    "com.livesubs.tv",
    "app.sonicsound",
    "org.videolan.vlc",
    "com.google.android.youtube.tv",
    "com.netflix.ninja",
    "com.disney.disneyplus",
    "tv.twitch.android.app",
    "com.kick.mobile",
    "ar.tvplayer.tv",
    "com.spotify.tv.android",
    "com.google.android.videos",
]

DEVICE_RELATIVE = "Android/data/dev.solon.bucklerhome/files/home-layout.json"


class HomeLayoutError(ValueError):
    """Invalid home-layout document."""


def schema_path() -> Path:
    return repo_root() / "schema" / "home-layout.v1.json"


def default_home_layout(
    *,
    layout: str = "banner",
    packages: list[str] | None = None,
) -> dict[str, Any]:
    pkgs = packages if packages is not None else list(DEFAULT_DOCK)
    return {
        "schema": 1,
        "layout": layout,
        "suggested_feed_url": None,
        "shelves": [
            {
                "id": "watch_next",
                "type": "watch_next",
                "title": "Up Next",
                "enabled": True,
                "packages": [],
            },
            {
                "id": "apps",
                "type": "apps",
                "title": "Apps",
                "enabled": True,
                "packages": pkgs,
            },
            {
                "id": "suggested",
                "type": "suggested",
                "title": "Suggested",
                "enabled": True,
                "packages": [],
            },
            {
                "id": "recents",
                "type": "recents",
                "title": "Recent",
                "enabled": False,
                "packages": [],
            },
            {
                "id": "library",
                "type": "library",
                "title": "Library",
                "enabled": True,
                "packages": [],
            },
        ],
    }


def validate_home_layout(doc: dict[str, Any]) -> None:
    if not isinstance(doc, dict):
        raise HomeLayoutError("document must be an object")
    if doc.get("schema") != 1:
        raise HomeLayoutError(f"schema must be 1, got {doc.get('schema')!r}")
    layout = doc.get("layout")
    if layout not in ("icon", "poster", "banner"):
        raise HomeLayoutError(f"layout must be icon|poster|banner, got {layout!r}")
    shelves = doc.get("shelves")
    if not isinstance(shelves, list) or not shelves:
        raise HomeLayoutError("shelves must be a non-empty array")
    allowed = {"apps", "library", "recents", "watch_next", "suggested"}
    for i, shelf in enumerate(shelves):
        if not isinstance(shelf, dict):
            raise HomeLayoutError(f"shelves[{i}] must be an object")
        for key in ("id", "type", "title", "enabled"):
            if key not in shelf:
                raise HomeLayoutError(f"shelves[{i}] missing {key}")
        if shelf["type"] not in allowed:
            raise HomeLayoutError(f"shelves[{i}].type invalid: {shelf['type']!r}")
        if not isinstance(shelf["enabled"], bool):
            raise HomeLayoutError(f"shelves[{i}].enabled must be boolean")


def default_layout_path() -> Path:
    return journals_dir() / "home-layout.json"


def write_home_layout(path: Path | None = None, doc: dict[str, Any] | None = None) -> Path:
    payload = doc if doc is not None else default_home_layout()
    validate_home_layout(payload)
    out = path if path is not None else default_layout_path()
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    return out


def device_layout_path() -> str:
    return f"/sdcard/{DEVICE_RELATIVE}"


def push_home_layout(adb: Adb, local: Path) -> str:
    if not local.is_file():
        raise HomeLayoutError(f"missing layout file: {local}")
    doc = json.loads(local.read_text(encoding="utf-8"))
    validate_home_layout(doc)
    remote = device_layout_path()
    # Ensure parent exists on device
    parent = remote.rsplit("/", 1)[0]
    adb.shell("mkdir", "-p", parent, check=False)
    adb.push(str(local), remote)
    return remote
