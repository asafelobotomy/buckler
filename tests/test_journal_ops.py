import json
from pathlib import Path

import pytest
from fakes import FakeAdb

from buckler.journal import Journal, iter_journals
from buckler.ops import apply_profile, check_drift, restore_all_journals, restore_journal
from buckler.policy import OUR_LAUNCHER
from buckler.profiles import SETUP_PROFILES


def test_refuse_apply_never_and_home_clean() -> None:
    adb = FakeAdb()
    with pytest.raises(ValueError, match="never"):
        apply_profile(adb, "never")
    with pytest.raises(ValueError, match="handover"):
        apply_profile(adb, "home-clean")


def test_journal_round_trip_restore(journals_dir: Path) -> None:
    adb = FakeAdb(
        present={"com.google.android.katniss", "com.nvidia.stats"},
        disabled=set(),
    )
    # apply privacy-core-ish manually via journal
    j = Journal(host=adb.host, profile="test")
    adb.disable_user("com.google.android.katniss")
    j.add(
        "com.google.android.katniss",
        before_disabled=False,
        after_disabled=True,
        reason="test",
    )
    path = j.save(journals_dir / "test-privacy.json")

    assert "com.google.android.katniss" in adb.disabled
    restored = restore_journal(adb, path)
    assert restored == ["com.google.android.katniss"]
    assert "com.google.android.katniss" not in adb.disabled


def test_iter_journals_skips_snapshots(journals_dir: Path) -> None:
    (journals_dir / "20260101-privacy-core.json").write_text(
        '{"host":"h","profile":"privacy-core","entries":[]}\n',
        encoding="utf-8",
    )
    (journals_dir / "snapshot-20260101.json").write_text(
        '{"schema":1,"host":"h","policy":{},"profiles":[]}\n',
        encoding="utf-8",
    )
    (journals_dir / "home-layout.json").write_text(
        '{"schema":1,"layout":"banner","shelves":[]}\n',
        encoding="utf-8",
    )
    names = [p.name for p in iter_journals()]
    assert "20260101-privacy-core.json" in names
    assert "snapshot-20260101.json" not in names
    assert "home-layout.json" not in names


def test_journal_load_rejects_layout_file(journals_dir: Path) -> None:
    path = journals_dir / "home-layout.json"
    path.write_text('{"schema":1,"layout":"banner","shelves":[]}\n', encoding="utf-8")
    with pytest.raises(ValueError, match="not a disable journal"):
        Journal.load(path)


def test_restore_all_journals(journals_dir: Path) -> None:
    adb = FakeAdb(
        present={"a.pkg", "b.pkg"},
        disabled={"a.pkg", "b.pkg"},
    )
    for name, pkg in (("one.json", "a.pkg"), ("two.json", "b.pkg")):
        j = Journal(host=adb.host, profile="t")
        j.add(pkg, before_disabled=False, after_disabled=True, reason="t")
        j.save(journals_dir / name)

    results = restore_all_journals(adb)
    assert len(results) == 2
    assert adb.disabled == set()


def test_check_drift_uses_setup_profiles(journals_dir: Path) -> None:
    # Ensure SETUP_PROFILES is the default desired set by marking a speech pkg present+enabled
    from buckler.profiles import load_profile

    speech_pkgs = load_profile("speech").packages
    assert speech_pkgs
    target = speech_pkgs[0]
    assert SETUP_PROFILES  # default desired set includes speech
    adb = FakeAdb(
        present={
            target,
            "com.nvidia.NvCPLSvc",
            "com.nvidia.nvaudiosvc",
            "com.nvidia.avsync",
            "com.dolby.android.audio.service",
        },
        disabled=set(),
        home=f"{OUR_LAUNCHER}/.MainActivity",
    )
    drifted, ai = check_drift(adb)
    assert target in drifted
    assert not ai
    assert journals_dir.is_dir()


def test_journal_load_ignores_unknown_entry_fields(tmp_path) -> None:
    path = tmp_path / "j.json"
    path.write_text(
        json.dumps(
            {
                "host": "h",
                "entries": [
                    {
                        "package": "a.b",
                        "action": "disable-user",
                        "reason": "r",
                        "before_disabled": False,
                        "future_field": 1,
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    assert Journal.load(path).entries[0].package == "a.b"
