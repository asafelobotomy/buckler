"""Screensaver photo push + media-access grant (no real adb)."""

from __future__ import annotations

from pathlib import Path

import pytest

from buckler.launcher_ops import (
    MEDIA_LISTENER,
    PHOTOS_DIR,
    grant_media_access,
    grant_settings_access,
    push_screensaver_photos,
)


class RecordingAdb:
    def __init__(self) -> None:
        self.shells: list[tuple[str, ...]] = []
        self.pushes: list[tuple[str, str]] = []

    def connect(self) -> str:
        return "connected"

    def shell(self, *args: str, check: bool = True) -> str:
        self.shells.append(args)
        return ""

    def push(self, local: str, remote: str) -> str:
        self.pushes.append((local, remote))
        return "ok"


def test_push_photos_sanitises_names(tmp_path: Path) -> None:
    photo = tmp_path / "my photo.JPG"
    photo.write_bytes(b"x")
    adb = RecordingAdb()
    pushed = push_screensaver_photos(adb, [photo])  # type: ignore[arg-type]
    assert pushed == [f"{PHOTOS_DIR}/my_photo.JPG"]
    assert adb.shells[0] == ("mkdir", "-p", PHOTOS_DIR)
    assert adb.pushes == [(str(photo), pushed[0])]


def test_push_photos_rejects_non_images(tmp_path: Path) -> None:
    script = tmp_path / "evil.sh"
    script.write_text("x")
    with pytest.raises(ValueError, match="not jpg/png/webp"):
        push_screensaver_photos(RecordingAdb(), [script])  # type: ignore[arg-type]


def test_push_photos_rejects_missing_files(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="no such file"):
        push_screensaver_photos(RecordingAdb(), [tmp_path / "gone.png"])  # type: ignore[arg-type]


def test_media_access_uses_notification_listener() -> None:
    adb = RecordingAdb()
    grant_media_access(adb)  # type: ignore[arg-type]
    assert adb.shells[-1] == ("cmd", "notification", "allow_listener", MEDIA_LISTENER)
    assert MEDIA_LISTENER.endswith(".NowPlayingListener")


def test_grant_settings_runs_appop_and_secure_grant() -> None:
    adb = RecordingAdb()
    ran = grant_settings_access(adb)  # type: ignore[arg-type]
    assert adb.shells == [
        ("appops", "set", "dev.solon.bucklerhome", "WRITE_SETTINGS", "allow"),
        ("appops", "set", "dev.solon.bucklerhome", "REQUEST_INSTALL_PACKAGES", "allow"),
        ("appops", "set", "dev.solon.bucklerhome", "GET_USAGE_STATS", "allow"),
        ("pm", "grant", "dev.solon.bucklerhome", "android.permission.WRITE_SECURE_SETTINGS"),
    ]
    assert len(ran) == 4
