"""Guided restore from disable journals, then doctor."""

from __future__ import annotations

from pathlib import Path

from buckler.adb import Adb
from buckler.journal import iter_journals, latest_journal
from buckler.ops import restore_all_journals, restore_journal
from buckler.setup_wizard import WizardReport
from buckler.tune import doctor
from buckler.wizard_phases import Echo, echo_to


def run_restore_wizard(
    adb: Adb,
    *,
    journal: Path | None = None,
    all_journals: bool = False,
    echo: Echo | None = None,
) -> WizardReport:
    """Undo disables then doctor.

    Preferred after multi-profile setup: ``all_journals=True`` (CLI ``--all``).
    Otherwise pass an explicit journal, or fall back to the latest disable journal.
    """
    report = WizardReport(host=adb.host, mode="restore", runtime="stock-adb")
    echo_to(echo, "=== Buckler restore-wizard ===")

    try:
        adb.connect()
        if all_journals:
            paths = iter_journals()
            if not paths:
                report.add("journal", False, "no journals found")
                return report
            results = restore_all_journals(adb)
            total = sum(len(restored) for _, restored in results)
            detail = f"{len(results)} journals, {total} packages enabled"
            echo_to(echo, detail)
            for path, pkgs in results:
                echo_to(echo, f"  {path.name}: {len(pkgs)}")
            report.add("restore", True, detail)
        else:
            path = journal or latest_journal()
            if path is None:
                report.add("journal", False, "no journals found")
                return report
            restored = restore_journal(adb, path)
            detail = f"{path.name}: {len(restored)} enabled"
            echo_to(echo, detail)
            report.add("restore", True, detail)
    except Exception as exc:  # noqa: BLE001
        report.add("restore", False, str(exc))
        return report

    try:
        doc = doctor(adb)
        for line in doc.lines:
            echo_to(echo, line)
        report.add("doctor", doc.ok)
    except Exception as exc:  # noqa: BLE001
        report.add("doctor", False, str(exc))

    echo_to(
        echo,
        "Stock NVIDIA factory reset still uses their OOBE — re-run `buckler setup` after.",
    )
    echo_to(echo, "On kitetvOS, factory reset will re-enter *our* first-boot instead.")
    return report

