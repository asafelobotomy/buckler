"""Decluttering from a computer: apps Buckler's Settings → Clean up → Apps found unused (read from
the TV and optionally disabled), and clearing every app's cache, which only adb is allowed to do."""

from __future__ import annotations

import json
from dataclasses import dataclass

from buckler.adb import Adb, AdbError
from buckler.ops import ApplyResult, apply_packages
from buckler.policy import OUR_LAUNCHER
from buckler.profiles import Profile

EXPORT_PATH = f"/sdcard/Android/data/{OUR_LAUNCHER}/files/unused-apps.json"
PROFILE = "unused"


@dataclass
class UnusedApp:
    package: str
    label: str
    system: bool
    last_used: str | None
    background_runs: int
    background_run_ms: int
    background_bytes: int

    def describe(self) -> str:
        parts = [f"last opened {self.last_used[:10]}" if self.last_used else "never opened"]
        if self.background_runs:
            minutes = self.background_run_ms // 60_000
            parts.append(f"ran in background {self.background_runs}x ({minutes} min)")
        if self.background_bytes >= 1_000_000:
            parts.append(f"{self.background_bytes // 1_000_000} MB background data")
        return ", ".join(parts)


@dataclass
class UnusedReport:
    generated_at: str
    days: int
    apps: list[UnusedApp]


def parse_report(text: str) -> UnusedReport:
    data = json.loads(text)
    apps = [
        UnusedApp(
            package=a["package"],
            label=a.get("label") or a["package"],
            system=bool(a.get("system")),
            last_used=a.get("last_used"),
            background_runs=int(a.get("background_runs") or 0),
            background_run_ms=int(a.get("background_run_ms") or 0),
            background_bytes=int(a.get("background_bytes") or 0),
        )
        for a in data.get("apps", [])
    ]
    return UnusedReport(generated_at=str(data.get("generated_at", "")), days=int(data["days"]), apps=apps)


def read_report(adb: Adb) -> UnusedReport:
    """The list Buckler last wrote (on returning Home, or opening Settings → Clean up → Apps)."""
    adb.connect()
    text = adb.shell("cat", EXPORT_PATH, check=False)
    if not text.lstrip().startswith("{"):
        raise AdbError(
            "No unused-apps list on the TV yet: open Buckler Settings → Clean up → Apps once "
            "(after `buckler launcher grant-settings`)."
        )
    return parse_report(text)


def disable_unused(adb: Adb, report: UnusedReport, packages: list[str] | None = None,
                   *, dry_run: bool = False) -> ApplyResult:
    """Disable the listed apps (or the chosen subset), journaled so `buckler restore` undoes it."""
    listed = [a.package for a in report.apps]
    chosen = listed if not packages else [p for p in packages if p in listed]
    profile = Profile(
        name=PROFILE,
        description=f"unused for {report.days} days (Buckler, {report.generated_at[:10]})",
        packages=chosen,
    )
    return apply_packages(adb, profile, dry_run=dry_run)


def free_kb(adb: Adb) -> int:
    """Free space on /data in KiB (0 if `df` gives nothing readable)."""
    lines = adb.shell("df", "-k", "/data", check=False).splitlines()
    fields = lines[-1].split() if len(lines) > 1 else []
    return int(fields[3]) if len(fields) > 3 and fields[3].isdigit() else 0


def clear_caches(adb: Adb) -> int:
    """Clear every app's cache (Android's own `pm trim-caches`); returns the KiB freed."""
    adb.connect()
    before = free_kb(adb)
    # Asking for more than the disk holds makes Android clear all caches it can.
    adb.shell("pm", "trim-caches", "999G")
    return max(free_kb(adb) - before, 0)
