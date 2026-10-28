"""Thin wrapper around the system adb binary."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
from dataclasses import dataclass


class AdbError(RuntimeError):
    """Raised when an adb command fails."""


# `adb shell` hands its arguments to the TV's shell as one line, so anything spliced into it must
# not contain shell syntax. Package names and components come from profiles, journals and the TV.
_PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$")
_COMPONENT = re.compile(r"^[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+$")


def checked_package(package: str) -> str:
    """[package] if it is a valid Android package name, else AdbError."""
    if not _PACKAGE.match(package):
        raise AdbError(f"Not a package name: {package!r}")
    return package


# adb connect to an absent host can hang for a minute or more.
CONNECT_TIMEOUT_S = 15.0


@dataclass
class Adb:
    host: str
    adb_bin: str = "adb"

    def __post_init__(self) -> None:
        if shutil.which(self.adb_bin) is None:
            raise AdbError(f"adb not found on PATH ({self.adb_bin})")
        if ":" not in self.host:
            self.host = f"{self.host}:5555"

    def connect(self) -> str:
        """Connect, then confirm the device answers.

        Reads use ``check=False``, so an offline device would otherwise look like one where every
        package is missing, and restore would report "nothing to enable" with exit 0.
        """
        out = self._run([self.adb_bin, "connect", self.host], check=False, timeout=CONNECT_TIMEOUT_S)
        state = self._run(
            [self.adb_bin, "-s", self.host, "get-state"], check=False, timeout=CONNECT_TIMEOUT_S
        )
        if state.stdout.strip() != "device":
            detail = (state.stderr or out.stdout or "").strip()
            raise AdbError(f"{self.host} is not reachable over adb ({detail or 'no device'})")
        return out.stdout.strip()

    def serial(self) -> str:
        return self.host

    def shell(self, *args: str, check: bool = True) -> str:
        cmd = [self.adb_bin, "-s", self.host, "shell", *args]
        result = self._run(cmd, check=check)
        return result.stdout.replace("\r", "").strip()

    def shell_raw(self, command: str, check: bool = True) -> str:
        return self.shell(command, check=check)

    def getprop(self, key: str) -> str:
        return self.shell("getprop", key, check=False)

    def pm_path(self, package: str) -> str | None:
        out = self.shell("pm", "path", checked_package(package), check=False)
        if not out or "package:" not in out:
            return None
        return out.split("package:", 1)[1].strip()

    def package_present(self, package: str) -> bool:
        return self.pm_path(package) is not None

    def package_disabled(self, package: str) -> bool:
        # `pm list packages -d X` filters by substring: match the whole line, so a disabled
        # com.nvidia.factorybundling does not count as com.nvidia.factory.
        out = self.shell("pm", "list", "packages", "-d", checked_package(package), check=False)
        return f"package:{package}" in (line.strip() for line in out.splitlines())

    def disable_user(self, package: str) -> str:
        return self.shell("pm", "disable-user", "--user", "0", checked_package(package))

    def force_stop(self, package: str) -> str:
        """Best-effort stop; some NVIDIA system services may respawn until reboot."""
        return self.shell("am", "force-stop", checked_package(package), check=False)

    def enable(self, package: str) -> str:
        return self.shell("pm", "enable", checked_package(package))

    def list_packages(self, flag: str | None = None) -> list[str]:
        args = ["pm", "list", "packages"]
        if flag:
            args.append(flag)
        out = self.shell(*args, check=False)
        pkgs: list[str] = []
        for line in out.splitlines():
            line = line.strip()
            if line.startswith("package:"):
                pkgs.append(line.removeprefix("package:"))
        return sorted(pkgs)

    def resolve_home(self) -> str:
        out = self.shell(
            "cmd",
            "package",
            "resolve-activity",
            "--brief",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.HOME",
            check=False,
        )
        lines = [ln.strip() for ln in out.splitlines() if ln.strip()]
        return lines[-1] if lines else ""

    def home_package(self) -> str:
        resolved = self.resolve_home()
        if "/" in resolved:
            return resolved.split("/", 1)[0]
        return resolved

    def install_apk(self, apk_path: str) -> str:
        result = self._run(
            [self.adb_bin, "-s", self.host, "install", "-r", "-t", apk_path],
            check=True,
        )
        return result.stdout.strip()

    def push(self, local: str, remote: str) -> str:
        result = self._run(
            [self.adb_bin, "-s", self.host, "push", local, remote],
            check=True,
        )
        return (result.stdout or result.stderr or "").strip()

    def set_home_activity(self, component: str) -> str:
        if not _COMPONENT.match(component):
            raise AdbError(f"Not a component name: {component!r}")
        return self.shell("cmd", "package", "set-home-activity", component, check=False)

    def reboot(self) -> None:
        self._run([self.adb_bin, "-s", self.host, "reboot"], check=False)

    def _run(
        self,
        cmd: list[str],
        *,
        check: bool,
        timeout: float | None = None,
    ) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        try:
            result = subprocess.run(
                cmd,
                check=False,
                capture_output=True,
                text=True,
                env=env,
                # adb shell reads stdin; it would swallow answers meant for click prompts.
                stdin=subprocess.DEVNULL,
                timeout=timeout,
            )
        except subprocess.TimeoutExpired as exc:
            raise AdbError(f"adb timed out after {timeout:.0f}s: {' '.join(cmd)}") from exc
        if check and result.returncode != 0:
            err = (result.stderr or result.stdout or "").strip()
            raise AdbError(f"adb failed ({result.returncode}): {' '.join(cmd)}\n{err}")
        return result
