"""Build, install, verify, and hand over HOME to buckler-home."""

from __future__ import annotations

import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

from buckler.adb import Adb, AdbError
from buckler.journal import Journal, journaled_packages
from buckler.paths import journals_dir, launcher_dir
from buckler.policy import (
    KNOWN_STOCK_LAUNCHERS,
    OUR_HOME_ACTIVITY,
    OUR_LAUNCHER,
    can_disable_home_owner,
)


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


PHOTOS_DIR = f"/sdcard/Android/data/{OUR_LAUNCHER}/files/screensaver"
MEDIA_LISTENER = f"{OUR_LAUNCHER}/{OUR_LAUNCHER}.NowPlayingListener"
PHOTO_SUFFIXES = frozenset({".jpg", ".jpeg", ".png", ".webp"})


def push_screensaver_photos(adb: Adb, files: list[Path]) -> list[str]:
    """Copy images into the folder the "My photos" screensaver reads; returns remote paths."""
    bad = [f.name for f in files if f.suffix.lower() not in PHOTO_SUFFIXES]
    if bad:
        raise ValueError(f"not jpg/png/webp: {', '.join(bad)}")
    missing = [str(f) for f in files if not f.is_file()]
    if missing:
        raise ValueError(f"no such file: {', '.join(missing)}")
    adb.connect()
    adb.shell("mkdir", "-p", PHOTOS_DIR)
    pushed: list[str] = []
    for f in files:
        remote = f"{PHOTOS_DIR}/{f.name.replace(' ', '_')}"
        adb.push(str(f), remote)
        pushed.append(remote)
    return pushed


def grant_media_access(adb: Adb) -> str:
    """Allow buckler-home to read media sessions (header Now Playing) without the Settings UI."""
    adb.connect()
    return adb.shell("cmd", "notification", "allow_listener", MEDIA_LISTENER)


SECURE_SETTINGS_PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
# WRITE_SETTINGS: screensaver delay and mono audio. REQUEST_INSTALL_PACKAGES: Buckler's own
# updates install without Android stopping to ask. GET_USAGE_STATS: Settings → Unused apps.
GRANTED_APPOPS = ("WRITE_SETTINGS", "REQUEST_INSTALL_PACKAGES", "GET_USAGE_STATS")


def grant_settings_access(adb: Adb) -> list[str]:
    """One-time grants for what Settings changes or reads on the TV.

    The appops in [GRANTED_APPOPS]; WRITE_SECURE_SETTINGS covers turning the screensaver off
    entirely and the remote's ≡ button. Returns the commands that ran.
    """
    adb.connect()
    ran: list[str] = []
    for appop in GRANTED_APPOPS:
        adb.shell("appops", "set", OUR_LAUNCHER, appop, "allow")
        ran.append(f"appops set {OUR_LAUNCHER} {appop} allow")
    adb.shell("pm", "grant", OUR_LAUNCHER, SECURE_SETTINGS_PERMISSION)
    ran.append(f"pm grant {OUR_LAUNCHER} {SECURE_SETTINGS_PERMISSION}")
    return ran


@dataclass
class HandoverResult:
    success: bool
    home_before: str
    home_after: str
    message: str
    journal_path: Path | None = None


def handover(adb: Adb, *, reboot: bool = False) -> HandoverResult:
    """Install (if needed), set HOME, disable known stock launchers, verify."""
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

    disabled_stocks: list[str] = []
    # Stock launchers already off but in no journal: only Buckler disables them, so record them
    # (restore must be able to bring a HOME back) rather than treating them as untouched.
    already_off: list[str] = []
    known = journaled_packages()
    try:
        for stock in sorted(KNOWN_STOCK_LAUNCHERS):
            if not adb.package_present(stock):
                continue
            if adb.package_disabled(stock):
                if stock not in known:
                    already_off.append(stock)
                continue
            ok, reason = can_disable_home_owner(adb, stock)
            if not ok:
                for pkg in disabled_stocks:
                    adb.enable(pkg)
                return HandoverResult(
                    False,
                    home_before,
                    home_before,
                    f"refusing to disable {stock}: {reason}",
                )
            adb.disable_user(stock)
            disabled_stocks.append(stock)
    except AdbError:
        # A failure halfway must not leave one stock launcher off with no journal.
        for pkg in disabled_stocks:
            adb.enable(pkg)
        raise

    home_after_pkg = adb.home_package()
    home_after = adb.resolve_home()

    if home_after_pkg != OUR_LAUNCHER:
        for pkg in disabled_stocks:
            if adb.package_disabled(pkg):
                adb.enable(pkg)
        return HandoverResult(
            False,
            home_before,
            home_after,
            f"HOME did not land on {OUR_LAUNCHER} (got {home_after}); stock re-enabled",
        )

    journal = Journal(host=adb.host, profile="home-clean")
    for stock in disabled_stocks:
        journal.add(
            stock,
            before_disabled=False,
            after_disabled=True,
            reason="HOME handover to buckler-home",
        )
    journal.notes.append(f"home_before={home_before}")
    journal.notes.append(f"home_after={home_after}")
    for stock in already_off:
        journal.add(
            stock,
            before_disabled=False,
            after_disabled=True,
            reason="found disabled at handover; recorded so restore re-enables it",
        )
    if not disabled_stocks:
        journal.notes.append("no stock launchers needed disabling (already clean)")
    path = journal.save(journals_dir() / "home-clean-handover.json", merge=True)

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
