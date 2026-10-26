"""Shared fakes for unit tests (no real adb / device)."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class FakeAdb:
    host: str = "192.168.178.23:5555"
    present: set[str] = field(default_factory=set)
    disabled: set[str] = field(default_factory=set)
    home: str = "com.google.android.tvlauncher/.MainActivity"
    props: dict[str, str] = field(default_factory=dict)
    settings: dict[tuple[str, str], str] = field(default_factory=dict)
    ps_output: str = ""
    force_stopped: list[str] = field(default_factory=list)

    def connect(self) -> str:
        return f"connected to {self.host}"

    def getprop(self, key: str) -> str:
        return self.props.get(key, "")

    def package_present(self, package: str) -> bool:
        return package in self.present

    def package_disabled(self, package: str) -> bool:
        return package in self.disabled

    def disable_user(self, package: str) -> str:
        self.disabled.add(package)
        return f"Package {package} new state: disabled-user"

    def enable(self, package: str) -> str:
        self.disabled.discard(package)
        return f"Package {package} new state: enabled"

    def list_packages(self, flag: str | None = None) -> list[str]:
        if flag == "-d":
            return sorted(self.disabled)
        if flag == "-3":
            return sorted(p for p in self.present if p.startswith("dev."))
        return sorted(self.present)

    def resolve_home(self) -> str:
        return self.home

    def home_package(self) -> str:
        resolved = self.resolve_home()
        if "/" in resolved:
            return resolved.split("/", 1)[0]
        return resolved

    def set_home_activity(self, component: str) -> str:
        self.home = component
        return "Success"

    def install_apk(self, apk_path: str) -> str:
        return f"Success [{apk_path}]"

    def shell(self, *args: str, check: bool = True) -> str:
        if args[:1] == ("ps",):
            return self.ps_output
        if args[:2] == ("am", "force-stop") and len(args) >= 3:
            self.force_stopped.append(args[2])
            return ""
        if args[:2] == ("settings", "get") and len(args) >= 4:
            return self.settings.get((args[2], args[3]), "null")
        if args[:2] == ("settings", "put") and len(args) >= 5:
            self.settings[(args[2], args[3])] = args[4]
            return ""
        return ""

    def reboot(self) -> None:
        return None
