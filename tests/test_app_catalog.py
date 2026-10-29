"""Catalog, dock rewrite, and wizard app phases. No network."""

from __future__ import annotations

import io
import json
from pathlib import Path

import pytest

from buckler.app_catalog import CatalogEntry, CatalogError, apply_dock, load_catalog
from buckler.home_layout import DEFAULT_DOCK
from buckler.profiles import load_never
from buckler.setup_wizard import WizardReport
from buckler.wizard_apps import fetch_verified_apk, run_app_phases
from buckler.wizard_phases import PHASES

_SHA = "ab" * 32


class FakeAdb:
    def __init__(self) -> None:
        self.installed: list[str] = []
        self.disabled: list[str] = []
        self.host = "192.168.1.1:5555"
        self.pushed: dict[str, str] = {}

    def package_present(self, package: str) -> bool:
        return False

    def install_apk(self, path: str) -> str:
        self.installed.append(path)
        return "Success"

    def disable_user(self, package: str) -> str:
        self.disabled.append(package)
        raise AssertionError(f"disable {package}")

    def shell(self, *args: str, check: bool = True) -> str:
        return ""

    def push(self, local: str, remote: str) -> str:
        self.pushed[remote] = Path(local).read_text(encoding="utf-8")
        return remote


def test_phase_order_inserts_app_pages() -> None:
    assert [phase.id for phase in PHASES] == [
        "connect",
        "probe",
        "privacy",
        "speech",
        "extras",
        "home",
        "alternatives",
        "suggestions",
        "screensaver",
        "tune",
        "verify",
        "snapshot",
        "finish",
    ]


def test_catalog_pins_known_packages() -> None:
    catalog = load_catalog()
    smarttube = catalog.require("swaps", "smarttube")
    google_tv = catalog.require("swaps", "google-tv")
    assert smarttube.package == "org.smarttube.stable"
    assert smarttube.dock == "replace-pin"
    assert "Crashlytics" in smarttube.note
    assert smarttube.stock_package == "com.google.android.youtube.tv"
    assert google_tv.dock == "unpin-only"
    assert google_tv.url == ""
    assert catalog.require("suggestions", "wireguard").package == "com.wireguard.android"
    blocked = load_never()
    for entry in catalog.entries:
        assert entry.stock_package not in blocked
        assert entry.package not in blocked


def test_dock_swaps_pins_without_dropping_other_apps() -> None:
    catalog = load_catalog()
    chosen = [
        catalog.require("swaps", "smarttube"),
        catalog.require("swaps", "google-tv"),
        catalog.require("swaps", "moonlight"),
    ]
    dock = apply_dock(list(DEFAULT_DOCK), chosen)
    youtube_at = DEFAULT_DOCK.index("com.google.android.youtube.tv")
    assert dock[youtube_at] == "org.smarttube.stable"
    assert "com.google.android.youtube.tv" not in dock
    assert "com.google.android.videos" not in dock
    assert dock[-1] == "com.limelight"
    assert "org.jellyfin.androidtv" in dock
    assert "org.videolan.vlc" in dock


def test_batch_setup_installs_nothing() -> None:
    adb = FakeAdb()
    report = WizardReport(host=adb.host, mode="test")
    result = run_app_phases(adb, report)
    assert result == {"swaps": [], "suggestions": []}
    assert adb.installed == []
    assert adb.disabled == []
    assert all(step.skipped for step in report.steps if step.name.startswith("swap:"))


def test_dry_run_does_not_fetch(monkeypatch: pytest.MonkeyPatch) -> None:
    def _fetch(*_args: object, **_kwargs: object) -> Path:
        raise AssertionError("fetched")

    monkeypatch.setattr("buckler.wizard_apps.fetch_verified_apk", _fetch)
    adb = FakeAdb()
    report = WizardReport(host=adb.host, mode="test")
    result = run_app_phases(
        adb,
        report,
        swaps=("smarttube", "google-tv"),
        dry_run=True,
    )
    assert result["swaps"] == ["smarttube", "google-tv"]
    assert adb.installed == []
    assert adb.disabled == []


def test_swap_installs_and_pins(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    apk = tmp_path / "smarttube.apk"
    apk.write_bytes(b"apk")
    monkeypatch.setattr("buckler.wizard_apps.fetch_verified_apk", lambda *_a, **_k: apk)
    adb = FakeAdb()
    report = WizardReport(host=adb.host, mode="test")
    result = run_app_phases(adb, report, swaps=("smarttube",), push_layout=True)
    assert result["swaps"] == ["smarttube"]
    assert adb.installed == [str(apk)]
    assert adb.disabled == []
    # Only a pins patch goes to the TV, never a whole layout that would replace its own.
    (remote, text), = adb.pushed.items()
    assert remote.endswith("home-layout-patch.json")
    ops = json.loads(text)["apps"]
    assert ops == [{"op": "replace", "from": "com.google.android.youtube.tv", "to": "org.smarttube.stable"}]


def test_interactive_suggestion_defaults_off() -> None:
    seen: list[bool] = []

    def ask(prompt: str, default: bool = True) -> bool:
        seen.append(default)
        return prompt.startswith("Install LocalSend")

    adb = FakeAdb()
    report = WizardReport(host=adb.host, mode="test")
    result = run_app_phases(adb, report, ask=ask, dry_run=True)
    assert seen
    assert all(item is False for item in seen)
    assert result == {"swaps": [], "suggestions": ["localsend"]}
    assert adb.installed == []


def test_unknown_id_fails_the_page() -> None:
    adb = FakeAdb()
    report = WizardReport(host=adb.host, mode="test")
    result = run_app_phases(adb, report, swaps=("not-an-app",))
    assert result["swaps"] == []
    assert any(step.name == "alternatives" and not step.ok for step in report.steps)
    assert adb.installed == []


def test_rejects_never_list_stock_package(tmp_path: Path) -> None:
    (tmp_path / "alternatives.yaml").write_text(
        f"""
entries:
  - id: bad
    label: Bad
    stock_label: Play Services
    stock_package: com.google.android.gms
    package: dev.example.app
    url: https://example.com/app.apk
    sha256: {_SHA}
    dock: replace-pin
    note: refused
""",
        encoding="utf-8",
    )
    (tmp_path / "suggestions.yaml").write_text("entries: []\n", encoding="utf-8")
    with pytest.raises(CatalogError, match="never-list"):
        load_catalog(tmp_path)


def test_hash_mismatch_deletes_partial(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        "buckler.wizard_apps.urllib.request.urlopen",
        lambda *_a, **_k: io.BytesIO(b"not-the-apk"),
    )
    entry = CatalogEntry(
        id="demo",
        label="Demo",
        group="suggestions",
        dock="append",
        package="dev.example.demo",
        url="https://example.com/demo.apk",
        sha256=_SHA,
        note="test",
    )
    cache = tmp_path / "cache"
    with pytest.raises(CatalogError, match="sha256"):
        fetch_verified_apk(entry, cache)
    assert list(cache.iterdir()) == []
