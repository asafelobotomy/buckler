"""Build, install, verify, and hand over HOME to shieldhome."""

from __future__ import annotations

import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

from shieldctl.adb import Adb
from shieldctl.journal import Journal
from shieldctl.paths import journals_dir, launcher_dir
from shieldctl.policy import OUR_HOME_ACTIVITY, OUR_LAUNCHER


STOCK_LAUNCHER = "com.google.android.tvlauncher"


@dataclass
class BuildResult:
    apk: Path
    log_tail: str


def _android_home() -> str:
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if home and Path(home).is_dir():
        return home
    default = Path.home() / "Android" / "Sdk"
    if default.is_dir():
        return str(default)
    raise RuntimeError(
        "ANDROID_HOME not set and ~/Android/Sdk not found. Install Android SDK platforms."
    )


def build_launcher() -> BuildResult:
    root = launcher_dir()
    if not (root / "gradlew").is_file():
        raise RuntimeError(f"Missing gradlew in {root}")
    env = os.environ.copy()
    env["ANDROID_HOME"] = _android_home()
    env["ANDROID_SDK_ROOT"] = env["ANDROID_HOME"]
    # Write local.properties
    (root / "local.properties").write_text(
        f"sdk.dir={env['ANDROID_HOME'].replace(chr(92), '/')}\n",
        encoding="utf-8",
    )
    gradlew = root / "gradlew"
    gradlew.chmod(gradlew.stat().st_mode | 0o111)
    result = subprocess.run(
        [str(gradlew), ":app:assembleRelease", "--no-daemon"],
        cwd=root,
        capture_output=True,
        text=True,
        env=env,
        check=False,
    )
    log = (result.stdout or "") + (result.stderr or "")
    if result.returncode != 0:
        raise RuntimeError(f"Gradle build failed:\n{log[-4000:]}")
    apk = root / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    if not apk.is_file():
        # debug keystore signed release might be app-release-unsigned — we sign with debug
        candidates = list((root / "app" / "build" / "outputs" / "apk" / "release").glob("*.apk"))
        if not candidates:
            raise RuntimeError(f"APK not found after build. Log:\n{log[-2000:]}")
        apk = candidates[0]
    return BuildResult(apk=apk, log_tail=log[-1500:])


def install_launcher(adb: Adb, apk: Path | None = None) -> str:
    adb.connect()
    if apk is None:
        apk = (
            launcher_dir()
            / "app"
            / "build"
            / "outputs"
            / "apk"
            / "release"
            / "app-release.apk"
        )
        if not apk.is_file():
            built = build_launcher()
            apk = built.apk
    return adb.install_apk(str(apk))


def verify_launcher_installed(adb: Adb) -> bool:
    adb.connect()
    return adb.package_present(OUR_LAUNCHER)


def home_is_ours(adb: Adb) -> bool:
    return adb.home_package() == OUR_LAUNCHER


@dataclass
class HandoverResult:
    success: bool
    home_before: str
    home_after: str
    message: str
    journal_path: Path | None = None


def handover(adb: Adb, *, reboot: bool = False) -> HandoverResult:
    """Install (if needed), set HOME, disable stock launcher, verify."""
    adb.connect()
    home_before = adb.resolve_home()

    if not adb.package_present(OUR_LAUNCHER):
        install_launcher(adb)

    if not adb.package_present(OUR_LAUNCHER):
        return HandoverResult(
            False,
            home_before,
            home_before,
            f"{OUR_LAUNCHER} failed to install",
        )

    # Try set-home first (often no-ops while stock priority=2)
    adb.set_home_activity(OUR_HOME_ACTIVITY)

    stock = STOCK_LAUNCHER
    if adb.package_present(stock) and not adb.package_disabled(stock):
        # Disable stock so our launcher can become HOME
        adb.disable_user(stock)

    home_after_pkg = adb.home_package()
    home_after = adb.resolve_home()

    if home_after_pkg != OUR_LAUNCHER:
        # Rollback: re-enable stock
        if adb.package_disabled(stock):
            adb.enable(stock)
        return HandoverResult(
            False,
            home_before,
            home_after,
            f"HOME did not land on {OUR_LAUNCHER} (got {home_after}); stock re-enabled",
        )

    journal = Journal(host=adb.host, profile="home-clean")
    journal.add(
        stock,
        before_disabled=False,
        after_disabled=True,
        reason="HOME handover to shieldhome",
    )
    journal.notes.append(f"home_before={home_before}")
    journal.notes.append(f"home_after={home_after}")
    path = journal.save(journals_dir() / "home-clean-handover.json")

    if reboot:
        adb.reboot()

    return HandoverResult(
        True,
        home_before,
        home_after,
        f"HOME is now {home_after}",
        journal_path=path,
    )


def which_java() -> str | None:
    return shutil.which("java")
