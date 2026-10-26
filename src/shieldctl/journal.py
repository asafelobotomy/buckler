"""JSON journals for restore / OTA drift detection."""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from shieldctl.paths import journals_dir


@dataclass
class JournalEntry:
    package: str
    action: str
    reason: str
    before_disabled: bool
    after_disabled: bool | None = None
    restore_command: str = ""


@dataclass
class Journal:
    host: str
    profile: str
    created_at: str = field(
        default_factory=lambda: datetime.now(timezone.utc).isoformat(timespec="seconds")
    )
    method: str = "pm disable-user --user 0"
    entries: list[JournalEntry] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    path: Path | None = None

    def add(
        self,
        package: str,
        *,
        before_disabled: bool,
        reason: str,
        after_disabled: bool | None = None,
        action: str = "disable-user",
    ) -> None:
        self.entries.append(
            JournalEntry(
                package=package,
                action=action,
                reason=reason,
                before_disabled=before_disabled,
                after_disabled=after_disabled,
                restore_command=f"adb shell pm enable {package}",
            )
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "host": self.host,
            "profile": self.profile,
            "created_at": self.created_at,
            "method": self.method,
            "notes": self.notes,
            "entries": [asdict(e) for e in self.entries],
        }

    def save(self, path: Path | None = None) -> Path:
        if path is None:
            stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
            path = journals_dir() / f"{stamp}-{self.profile}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.to_dict(), indent=2) + "\n", encoding="utf-8")
        self.path = path
        return path

    @classmethod
    def load(cls, path: Path) -> Journal:
        data = json.loads(path.read_text(encoding="utf-8"))
        entries = [JournalEntry(**e) for e in data.get("entries", [])]
        j = cls(
            host=data["host"],
            profile=data.get("profile", "unknown"),
            created_at=data.get("created_at", ""),
            method=data.get("method", ""),
            entries=entries,
            notes=list(data.get("notes") or []),
        )
        j.path = path
        return j


def latest_journal(profile: str | None = None) -> Path | None:
    files = sorted(journals_dir().glob("*.json"), reverse=True)
    for path in files:
        if profile is None or f"-{profile}.json" in path.name:
            return path
    return None


def seed_privacy_core_journal(host: str) -> Path:
    """Record disables already applied manually before the CLI existed."""
    j = Journal(host=host, profile="privacy-core")
    j.notes.append("Seeded from pre-CLI session (2026-10-26)")
    for pkg, reason in [
        ("com.google.android.katniss", "user: remove Google Assistant"),
        ("com.google.android.tvrecommendations", "user: no phone remote"),
    ]:
        j.add(
            pkg,
            before_disabled=False,
            after_disabled=True,
            reason=reason,
        )
    return j.save(journals_dir() / "20261026-seed-privacy-core.json")
