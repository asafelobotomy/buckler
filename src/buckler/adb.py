"""Thin wrapper around the system adb binary."""

from __future__ import annotations

import os
import shutil
import subprocess
from dataclasses import dataclass


class AdbError(RuntimeError):
    """Raised when an adb command fails."""


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
        out = self._run([self.adb_bin, "connect", self.host], check=False)
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
        out = self.shell("pm", "path", package, check=False)
        if not out or "package:" not in out:
            return None
        return out.split("package:", 1)[1].strip()

    def package_present(self, package: str) -> bool:
        return self.pm_path(package) is not None

    def package_disabled(self, package: str) -> bool:
        out = self.shell("pm", "list", "packages", "-d", package, check=False)
        return package in out

    def disable_user(self, package: str) -> str:
        return self.shell("pm", "disable-user", "--user", "0", package)

    def force_stop(self, package: str) -> str:
        """Best-effort stop; some NVIDIA system services may respawn until reboot."""
        return self.shell("am", "force-stop", package, check=False)

    def enable(self, package: str) -> str:
        return self.shell("pm", "enable", package)

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

    def set_home_activity(self, component: str) -> str:
        return self.shell("cmd", "package", "set-home-activity", component, check=False)

    def reboot(self) -> None:
        self._run([self.adb_bin, "-s", self.host, "reboot"], check=False)

    def _run(
        self,
        cmd: list[str],
        *,
        check: bool,
    ) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        result = subprocess.run(
            cmd,
            check=False,
            capture_output=True,
            text=True,
            env=env,
        )
        if check and result.returncode != 0:
            err = (result.stderr or result.stdout or "").strip()
            raise AdbError(f"adb failed ({result.returncode}): {' '.join(cmd)}\n{err}")
        return result
