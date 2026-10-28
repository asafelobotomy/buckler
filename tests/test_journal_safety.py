"""Journal and restore safety: merges, restore records, drift, exact matching, offline devices."""

import json
import subprocess
from pathlib import Path

import pytest
from fakes import FakeAdb

from buckler.adb import Adb, AdbError
from buckler.journal import Journal, latest_journal
from buckler.launcher_ops import handover
from buckler.ops import apply_profile, check_drift, restore_journal
from buckler.policy import OUR_LAUNCHER
from buckler.tune import BACKDROP, disable_backdrop

AI = {
    "com.nvidia.NvCPLSvc",
    "com.nvidia.nvaudiosvc",
    "com.nvidia.avsync",
    "com.dolby.android.audio.service",
}
TV_LAUNCHER = "com.google.android.tvlauncher"


def _entries(path: Path) -> list[str]:
    return [e["package"] for e in json.loads(path.read_text())["entries"]]


def test_second_handover_keeps_first_record(journals_dir: Path) -> None:
    adb = FakeAdb(present={OUR_LAUNCHER, TV_LAUNCHER}, home=f"{TV_LAUNCHER}/.MainActivity")
    first = handover(adb)
    assert first.success and TV_LAUNCHER in adb.disabled
    second = handover(adb)
    assert second.success
    assert _entries(journals_dir / "home-clean-handover.json") == [TV_LAUNCHER]


def test_handover_records_stock_launcher_found_disabled(journals_dir: Path) -> None:
    adb = FakeAdb(
        present={OUR_LAUNCHER, TV_LAUNCHER},
        disabled={TV_LAUNCHER},
        home=f"{OUR_LAUNCHER}/.MainActivity",
    )
    assert handover(adb).success
    path = journals_dir / "home-clean-handover.json"
    assert _entries(path) == [TV_LAUNCHER]
    assert restore_journal(adb, path) == [TV_LAUNCHER]


def test_second_screensaver_run_keeps_backdrop(journals_dir: Path) -> None:
    adb = FakeAdb(present={BACKDROP, OUR_LAUNCHER})
    disable_backdrop(adb)
    disable_backdrop(adb)
    assert _entries(journals_dir / "screensaver.json") == [BACKDROP]


def test_latest_journal_uses_created_at_not_name(journals_dir: Path) -> None:
    old = Journal(host="h", profile="screensaver", created_at="2026-01-01T00:00:00+00:00")
    old.save(journals_dir / "screensaver.json")
    new = Journal(host="h", profile="extras", created_at="2026-06-01T00:00:00+00:00")
    path = new.save(journals_dir / "20260601-extras.json")
    assert latest_journal() == path


def test_restored_packages_are_not_drift(journals_dir: Path) -> None:
    adb = FakeAdb(present={"a.pkg"} | AI, disabled={"a.pkg"}, home=f"{OUR_LAUNCHER}/.Main")
    j = Journal(host=adb.host, profile="custom", created_at="2026-01-01T00:00:00+00:00")
    j.add("a.pkg", before_disabled=False, after_disabled=True, reason="t")
    path = j.save(journals_dir / "custom.json")
    assert restore_journal(adb, path) == ["a.pkg"]
    drifted, _ = check_drift(adb, fix=True)
    assert drifted == []
    assert "a.pkg" not in adb.disabled


def test_skipped_setup_profile_is_not_drift(journals_dir: Path) -> None:
    from buckler.profiles import load_profile

    target = load_profile("speech").packages[0]
    adb = FakeAdb(present={target} | AI, home=f"{OUR_LAUNCHER}/.Main")
    drifted, _ = check_drift(adb)
    assert target not in drifted


def test_check_fix_never_disables_stock_home_without_ours(journals_dir: Path) -> None:
    j = Journal(host="h", profile="home-clean")
    j.add(TV_LAUNCHER, before_disabled=False, after_disabled=True, reason="t")
    j.save(journals_dir / "home-clean-handover.json")
    adb = FakeAdb(
        present={OUR_LAUNCHER, TV_LAUNCHER} | AI,
        disabled={OUR_LAUNCHER},
        home=f"{TV_LAUNCHER}/.MainActivity",
    )
    drifted, _ = check_drift(adb, fix=True)
    assert drifted == [TV_LAUNCHER]
    assert TV_LAUNCHER not in adb.disabled


def test_apply_journals_partial_progress_on_adb_error(journals_dir: Path) -> None:
    from buckler.profiles import load_profile

    pkgs = load_profile("extras").packages[:2]
    adb = FakeAdb(present=set(pkgs), home=f"{OUR_LAUNCHER}/.Main")
    real = adb.disable_user

    def flaky(pkg: str) -> str:
        if pkg == pkgs[1]:
            raise AdbError("boom")
        return real(pkg)

    adb.disable_user = flaky  # type: ignore[method-assign]
    with pytest.raises(AdbError):
        apply_profile(adb, "extras")
    saved = list(journals_dir.glob("*-extras.json"))
    assert len(saved) == 1
    assert pkgs[0] in _entries(saved[0])


def _adb_with(outputs: dict[str, str], monkeypatch: pytest.MonkeyPatch) -> Adb:
    monkeypatch.setattr("buckler.adb.shutil.which", lambda _b: "/usr/bin/adb")
    adb = Adb(host="10.0.0.2")

    def run(
        cmd: list[str], *, check: bool, timeout: float | None = None
    ) -> subprocess.CompletedProcess[str]:
        key = next((k for k in outputs if k in " ".join(cmd)), "")
        return subprocess.CompletedProcess(cmd, 0, outputs.get(key, ""), "")

    adb._run = run  # type: ignore[method-assign]
    return adb


def test_package_disabled_matches_whole_name(monkeypatch: pytest.MonkeyPatch) -> None:
    adb = _adb_with({"packages -d": "package:com.nvidia.factorybundling\n"}, monkeypatch)
    assert not adb.package_disabled("com.nvidia.factory")
    assert adb.package_disabled("com.nvidia.factorybundling")


def test_connect_raises_when_device_is_offline(monkeypatch: pytest.MonkeyPatch) -> None:
    adb = _adb_with({"get-state": "offline\n"}, monkeypatch)
    with pytest.raises(AdbError, match="not reachable"):
        adb.connect()
    ok = _adb_with({"get-state": "device\n"}, monkeypatch)
    ok.connect()
