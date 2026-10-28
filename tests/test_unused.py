import json
from pathlib import Path

import pytest
from fakes import FakeAdb

from buckler.adb import AdbError
from buckler.journal import iter_journals
from buckler.ops import restore_all_journals
from buckler.unused import disable_unused, parse_report, read_report

REPORT = {
    "generated_at": "2026-10-28T16:19:53Z",
    "days": 30,
    "apps": [
        {"package": "com.google.android.videos", "label": "Google TV", "system": False,
         "last_used": None, "background_runs": 0, "background_run_ms": 0,
         "background_bytes": 2_837_961},
        {"package": "com.google.android.tv", "label": "Live Channels", "system": True,
         "last_used": "2026-09-01T10:00:00Z", "background_runs": 40,
         "background_run_ms": 1_800_000, "background_bytes": 0},
        # The TV should never list this; the CLI refuses it anyway.
        {"package": "com.nvidia.ota", "label": "System upgrade", "system": True,
         "last_used": None, "background_runs": 180, "background_run_ms": 0,
         "background_bytes": 0},
    ],
}


class ReportAdb(FakeAdb):
    report: str = json.dumps(REPORT)

    def shell(self, *args: str, check: bool = True) -> str:
        if args[:1] == ("cat",):
            return self.report
        return super().shell(*args, check=check)


def make_adb() -> ReportAdb:
    return ReportAdb(present={"com.google.android.videos", "com.google.android.tv", "com.nvidia.ota"})


def test_report_reads_and_describes() -> None:
    report = parse_report(json.dumps(REPORT))
    assert report.days == 30 and len(report.apps) == 3
    assert report.apps[0].describe() == "never opened, 2 MB background data"
    assert report.apps[1].describe() == "last opened 2026-09-01, ran in background 40x (30 min)"


def test_missing_list_explains_what_to_do() -> None:
    adb = make_adb()
    adb.report = "cat: No such file or directory"
    with pytest.raises(AdbError, match="Settings → Apps"):
        read_report(adb)  # type: ignore[arg-type]


def test_apply_disables_listed_apps_but_never_the_never_list(journals_dir: Path) -> None:
    adb = make_adb()
    result = disable_unused(adb, read_report(adb))  # type: ignore[arg-type]
    assert set(result.changed) == {"com.google.android.videos", "com.google.android.tv"}
    assert "com.nvidia.ota" in result.blocked and "com.nvidia.ota" not in adb.disabled
    assert [p.name for p in iter_journals()] and result.journal.profile == "unused"

    restore_all_journals(adb)  # type: ignore[arg-type]
    assert not adb.disabled


def test_apply_can_pick_a_subset(journals_dir: Path) -> None:
    adb = make_adb()
    result = disable_unused(adb, read_report(adb), ["com.google.android.tv", "not.listed"])  # type: ignore[arg-type]
    assert result.changed == ["com.google.android.tv"]
