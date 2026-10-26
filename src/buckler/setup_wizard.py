"""Original multi-step setup / restore wizard (stock path → kitetvOS bridge)."""

from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import asdict, dataclass, field
from datetime import UTC, datetime
from pathlib import Path

from buckler.adb import Adb
from buckler.home_layout import push_home_layout, write_home_layout
from buckler.journal import Journal, iter_journals, latest_journal
from buckler.launcher_ops import build_launcher, handover, install_launcher
from buckler.ops import apply_profile, format_probe, probe, restore_all_journals, restore_journal
from buckler.paths import journals_dir, repo_root
from buckler.policy import OUR_LAUNCHER, verify_ai_stack
from buckler.profiles import SETUP_PROFILES, load_profile
from buckler.schema_validate import validate_desired_state
from buckler.tune import (
    compile_launcher_speed,
    disable_backdrop,
    doctor,
    set_animation_scales,
)
from buckler.wizard_phases import PHASES, banner

MEDIA_SHELL = "com.google.android.apps.mediashell"

Echo = Callable[[str], None]
Ask = Callable[[str], bool]


@dataclass
class StepResult:
    name: str
    ok: bool
    detail: str = ""
    skipped: bool = False


@dataclass
class WizardReport:
    host: str
    mode: str
    runtime: str = "stock-adb"
    started_at: str = field(
        default_factory=lambda: datetime.now(UTC).isoformat(timespec="seconds")
    )
    steps: list[StepResult] = field(default_factory=list)
    snapshot_path: Path | None = None

    def add(
        self,
        name: str,
        ok: bool,
        detail: str = "",
        *,
        skipped: bool = False,
    ) -> None:
        self.steps.append(StepResult(name=name, ok=ok, detail=detail, skipped=skipped))

    @property
    def ok(self) -> bool:
        return all(s.ok for s in self.steps)

    def to_dict(self) -> dict:
        return {
            "host": self.host,
            "mode": self.mode,
            "runtime": self.runtime,
            "started_at": self.started_at,
            "ok": self.ok,
            "steps": [asdict(s) for s in self.steps],
            "snapshot_path": str(self.snapshot_path) if self.snapshot_path else None,
        }


def _echo(echo: Echo | None, msg: str) -> None:
    if echo:
        echo(msg)


def _ask(ask: Ask | None, prompt: str) -> bool:
    if ask is None:
        return True
    return ask(prompt)


def export_snapshot(adb: Adb, path: Path | None = None) -> Path:
    adb.connect()
    p = probe(adb)
    desired_disabled: list[str] = []
    for name in SETUP_PROFILES:
        desired_disabled.extend(load_profile(name).packages)
    for journal_path in iter_journals():
        j = Journal.load(journal_path)
        for e in j.entries:
            if e.action == "disable-user":
                desired_disabled.append(e.package)
    desired_disabled = sorted(set(desired_disabled))

    if path is None:
        stamp = datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
        path = journals_dir() / f"snapshot-{stamp}.json"

    schema_ref = str(repo_root() / "schema" / "desired-state.v1.json")
    doc = {
        "schema": 1,
        "$schema": schema_ref,
        "created_at": datetime.now(UTC).isoformat(timespec="seconds"),
        "runtime": "stock-adb",
        "host": adb.host,
        "device": {
            "model": p.model,
            "codename": p.device,
            "build": p.build,
            "security_patch": p.security_patch,
            "home": p.home,
        },
        "policy": {
            "keep_ai_upscaling": True,
            "keep_chromecast": True,
            "disable_mics": True,
            "launcher": OUR_LAUNCHER,
            "screensaver": f"{OUR_LAUNCHER}/.ClockDream",
            "animation_scale": 0.5,
        },
        "profiles": list(SETUP_PROFILES) + ["home-clean"],
        "desired_disabled": desired_disabled,
        "actually_disabled": p.disabled,
        "ai_problems": p.ai_problems,
        "home_layout": "schema/home-layout.v1.json",
    }
    validate_desired_state(doc)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")
    return path


def run_setup(
    adb: Adb,
    *,
    dry_run: bool = False,
    skip_build: bool = False,
    skip_home_layout: bool = False,
    interactive: bool = False,
    echo: Echo | None = None,
    ask: Ask | None = None,
) -> WizardReport:
    """Multi-step setup. interactive=True prompts before mutating phases."""
    report = WizardReport(
        host=adb.host,
        mode="setup" + ("-dry-run" if dry_run else ""),
        runtime="stock-adb",
    )
    total = len(PHASES)
    _echo(echo, "=== Buckler multi-step setup ===")
    _echo(echo, "Stock path now · same phases → kitetvOS first-boot later")
    _echo(echo, f"Phases: {', '.join(p.id for p in PHASES)}")

    # --- connect ---
    ph = PHASES[0]
    _echo(echo, banner(ph, 0, total))
    try:
        adb.connect()
        report.add("connect", True, adb.host)
    except Exception as exc:  # noqa: BLE001
        report.add("connect", False, str(exc))
        return report

    # --- probe ---
    ph = PHASES[1]
    _echo(echo, banner(ph, 1, total))
    try:
        p = probe(adb)
        _echo(echo, format_probe(p))
        report.add("probe", True, p.device)
    except Exception as exc:  # noqa: BLE001
        report.add("probe", False, str(exc))
        return report

    # Present privacy once with both profiles
    _echo(echo, banner(PHASES[2], 2, total))
    if interactive and not _ask(ask, "Apply privacy profiles (privacy-core + minimal)?"):
        report.add("apply:privacy-core", True, "skipped by user", skipped=True)
        report.add("apply:minimal", True, "skipped by user", skipped=True)
    else:
        for name in ("privacy-core", "minimal"):
            try:
                result = apply_profile(adb, name, dry_run=dry_run)
                detail = (
                    f"changed={len(result.changed)} already={len(result.skipped_already)}"
                )
                _echo(echo, f"  [{name}] {detail}")
                report.add(f"apply:{name}", True, detail)
            except Exception as exc:  # noqa: BLE001
                report.add(f"apply:{name}", False, str(exc))
                if not dry_run:
                    return report

    _echo(echo, banner(PHASES[3], 3, total))
    if interactive and not _ask(ask, "Disable mics / Assistant (speech profile)?"):
        report.add("apply:speech", True, "skipped by user", skipped=True)
    else:
        try:
            result = apply_profile(adb, "speech", dry_run=dry_run)
            detail = f"changed={len(result.changed)} already={len(result.skipped_already)}"
            _echo(echo, f"  [speech] {detail}")
            report.add("apply:speech", True, detail)
        except Exception as exc:  # noqa: BLE001
            report.add("apply:speech", False, str(exc))
            if not dry_run:
                return report

    _echo(echo, banner(PHASES[4], 4, total))
    if interactive and not _ask(ask, "Apply extras (factory/SMB/OSC/welcome)?"):
        report.add("apply:extras", True, "skipped by user", skipped=True)
    else:
        try:
            result = apply_profile(adb, "extras", dry_run=dry_run)
            detail = f"changed={len(result.changed)} already={len(result.skipped_already)}"
            _echo(echo, f"  [extras] {detail}")
            report.add("apply:extras", True, detail)
        except Exception as exc:  # noqa: BLE001
            report.add("apply:extras", False, str(exc))
            if not dry_run:
                return report

    if dry_run:
        for pid in ("home", "screensaver", "tune", "verify"):
            report.add(pid, True, "skipped (dry-run)", skipped=True)
        snap = export_snapshot(adb)
        report.snapshot_path = snap
        report.add("snapshot", True, str(snap))
        _echo(echo, banner(PHASES[9], 9, total))
        _echo(echo, f"snapshot → {snap}")
        _echo(echo, banner(PHASES[10], 10, total))
        _echo(echo, "dry-run complete")
        report.add("finish", True, "dry-run")
        return report

    # --- home ---
    _echo(echo, banner(PHASES[5], 5, total))
    if interactive and not _ask(ask, "Install buckler-home and take over HOME?"):
        report.add("launcher", True, "skipped by user", skipped=True)
    else:
        try:
            if not skip_build:
                built = build_launcher()
                _echo(echo, f"  built {built.apk}")
            install_launcher(adb)
            hand = handover(adb, reboot=False)
            report.add("launcher", hand.success, hand.message)
            _echo(echo, f"  {hand.message}")
            if not hand.success:
                return report
            if not skip_home_layout:
                try:
                    local = write_home_layout()
                    remote = push_home_layout(adb, local)
                    report.add("home_layout", True, remote)
                    _echo(echo, f"  home-layout → {remote}")
                except Exception as exc:  # noqa: BLE001
                    report.add("home_layout", False, str(exc))
        except Exception as exc:  # noqa: BLE001
            report.add("launcher", False, str(exc))
            return report

    # --- screensaver ---
    _echo(echo, banner(PHASES[6], 6, total))
    if interactive and not _ask(ask, "Set ClockDream screensaver and disable Backdrop?"):
        report.add("screensaver", True, "skipped by user", skipped=True)
    else:
        try:
            journal = disable_backdrop(adb)
            report.add("screensaver", True, str(journal.path))
            _echo(echo, f"  screensaver → ClockDream ({journal.path})")
        except Exception as exc:  # noqa: BLE001
            report.add("screensaver", False, str(exc))

    # --- tune ---
    _echo(echo, banner(PHASES[7], 7, total))
    if interactive and not _ask(ask, "Apply animation 0.5 + speed-compile?"):
        report.add("tune", True, "skipped by user", skipped=True)
    else:
        try:
            set_animation_scales(adb, 0.5)
            compile_launcher_speed(adb)
            report.add("tune", True, "anim=0.5 + speed-compile")
        except Exception as exc:  # noqa: BLE001
            report.add("tune", False, str(exc))

    # --- verify ---
    _echo(echo, banner(PHASES[8], 8, total))
    if adb.package_present(MEDIA_SHELL) and adb.package_disabled(MEDIA_SHELL):
        adb.enable(MEDIA_SHELL)
        report.add("chromecast", False, "was disabled — re-enabled")
    else:
        report.add("chromecast", True, "mediashell kept")

    ai = verify_ai_stack(adb)
    report.add("ai_stack", not ai, "; ".join(ai) if ai else "OK")

    try:
        doc = doctor(adb)
        for line in doc.lines:
            _echo(echo, f"  {line}")
        report.add("doctor", doc.ok, "ok" if doc.ok else "issues")
    except Exception as exc:  # noqa: BLE001
        report.add("doctor", False, str(exc))

    # --- snapshot ---
    _echo(echo, banner(PHASES[9], 9, total))
    snap = export_snapshot(adb)
    report.snapshot_path = snap
    report.add("snapshot", True, str(snap))
    _echo(echo, f"  wrote {snap}")
    _echo(echo, "  schema: schema/desired-state.v1.json (shared with kitetvOS)")

    # --- finish ---
    _echo(echo, banner(PHASES[10], 10, total))
    _echo(echo, "  Stock setup finished.")
    _echo(echo, "  Turn Network debugging OFF when done.")
    _echo(echo, "  Next OS step: see docs/kitetvos-bridge.md")
    report.add("finish", True, "complete")
    return report


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
    _echo(echo, "=== Buckler restore-wizard ===")

    try:
        adb.connect()
        if all_journals:
            paths = iter_journals()
            if not paths:
                report.add("journal", False, "no journals found")
                return report
            results = restore_all_journals(adb)
            total = sum(len(r) for _, r in results)
            detail = f"{len(results)} journals, {total} packages enabled"
            _echo(echo, detail)
            for path, pkgs in results:
                _echo(echo, f"  {path.name}: {len(pkgs)}")
            report.add("restore", True, detail)
        else:
            path = journal or latest_journal()
            if path is None:
                report.add("journal", False, "no journals found")
                return report
            restored = restore_journal(adb, path)
            detail = f"{path.name}: {len(restored)} enabled"
            _echo(echo, detail)
            report.add("restore", True, detail)
    except Exception as exc:  # noqa: BLE001
        report.add("restore", False, str(exc))
        return report

    try:
        doc = doctor(adb)
        for line in doc.lines:
            _echo(echo, line)
        report.add("doctor", doc.ok)
    except Exception as exc:  # noqa: BLE001
        report.add("doctor", False, str(exc))

    _echo(
        echo,
        "Stock NVIDIA factory reset still uses their OOBE — re-run `buckler setup` after.",
    )
    _echo(echo, "On kitetvOS, factory reset will re-enter *our* first-boot instead.")
    return report
