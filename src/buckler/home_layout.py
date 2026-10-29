"""home-layout.v1 defaults, validation, and device push helpers."""

from __future__ import annotations

import json
import tempfile
from pathlib import Path
from typing import Any

from buckler.adb import Adb
from buckler.paths import journals_dir

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


def default_home_layout(
    *,
    layout: str = "banner",
    packages: list[str] | None = None,
    only_if_new: bool = False,
) -> dict[str, Any]:
    """Buckler's starting layout. ``only_if_new``: the TV takes it only if it has no layout of its own."""
    pkgs = packages if packages is not None else list(DEFAULT_DOCK)
    doc: dict[str, Any] = {
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
    if only_if_new:
        doc["only_if_new"] = True
    return doc


def validate_home_layout(doc: dict[str, Any]) -> None:
    if not isinstance(doc, dict):
        raise HomeLayoutError("document must be an object")
    if doc.get("schema") != 1:
        raise HomeLayoutError(f"schema must be 1, got {doc.get('schema')!r}")
    layout = doc.get("layout")
    if layout not in ("icon", "poster", "banner"):
        raise HomeLayoutError(f"layout must be icon|poster|banner, got {layout!r}")
    tile_size = doc.get("tile_size", "medium")
    if tile_size not in ("thin", "small", "medium", "large"):
        raise HomeLayoutError(f"tile_size must be thin|small|medium|large, got {tile_size!r}")
    stage_size = doc.get("stage_size", "medium")
    if stage_size not in ("small", "medium", "large"):
        raise HomeLayoutError(f"stage_size must be small|medium|large, got {stage_size!r}")
    shelves = doc.get("shelves")
    if not isinstance(shelves, list) or not shelves:
        raise HomeLayoutError("shelves must be a non-empty array")
    allowed = {"apps", "library", "recents", "watch_next", "suggested"}
    seen_ids: set[str] = set()
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
        shelf_id = shelf["id"]
        if not isinstance(shelf_id, str) or not shelf_id:
            raise HomeLayoutError(f"shelves[{i}].id must be a non-empty string")
        if shelf_id in seen_ids:
            raise HomeLayoutError(f"shelves[{i}].id duplicate: {shelf_id!r}")
        seen_ids.add(shelf_id)
        packages = shelf.get("packages", [])
        if not isinstance(packages, list) or not all(
            isinstance(pkg, str) and pkg for pkg in packages
        ):
            raise HomeLayoutError(f"shelves[{i}].packages must be a list of strings")
        if len(set(packages)) != len(packages):
            raise HomeLayoutError(f"shelves[{i}].packages contains duplicates")


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


DEVICE_PATCH_RELATIVE = "Android/data/dev.solon.bucklerhome/files/home-layout-patch.json"
PIN_OPS = ("replace", "remove", "append")


def pins_patch(ops: list[dict[str, str]]) -> dict[str, Any]:
    """A patch that changes only the Apps pins, applied by buckler-home to the pins it has now.

    Each op is ``{"op": "replace", "from": A, "to": B}``, ``{"op": "remove", "package": A}`` or
    ``{"op": "append", "package": A}``, the catalog's dock rules, so nothing else on Home changes.
    """
    for i, op in enumerate(ops):
        if op.get("op") not in PIN_OPS:
            raise HomeLayoutError(f"apps[{i}].op must be one of {PIN_OPS}")
        names = [op.get(k) for k in ("from", "to", "package") if k in op]
        if not names or not all(isinstance(n, str) and n for n in names):
            raise HomeLayoutError(f"apps[{i}] needs package names")
    return {"schema": 1, "apps": ops}


def push_pins_patch(adb: Adb, ops: list[dict[str, str]]) -> str:
    """Push a pins patch (see [pins_patch]); returns the path on the TV."""
    doc = pins_patch(ops)
    remote = f"/sdcard/{DEVICE_PATCH_RELATIVE}"
    with tempfile.TemporaryDirectory() as tmp:
        local = Path(tmp) / "home-layout-patch.json"
        local.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
        adb.shell("mkdir", "-p", remote.rsplit("/", 1)[0], check=False)
        adb.push(str(local), remote)
    return remote


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
