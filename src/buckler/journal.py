"""JSON journals for restore / OTA drift detection."""

from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass, field, fields
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from buckler.paths import journals_dir

# Files that live under journals/ but are not disable/restore journals.
_NON_JOURNAL_NAMES = frozenset(
    {
        "home-layout.json",
        "covers-index.json",
    }
)


@dataclass
class JournalEntry:
    package: str
    action: str
    reason: str
    before_disabled: bool
    after_disabled: bool | None = None
    restore_command: str = ""
    # Set by restore; check --fix leaves a package alone once its latest event is a restore.
    restored_at: str | None = None


@dataclass
class Journal:
    host: str
    profile: str
    created_at: str = field(
        default_factory=lambda: datetime.now(UTC).isoformat(timespec="seconds")
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

    def save(self, path: Path | None = None, *, merge: bool = False) -> Path:
        """Write atomically. ``merge`` keeps entries already in a fixed-name journal.

        Handover and screensaver reuse one file per run; without merging, a second run that
        finds everything already disabled would replace the record of what it disabled first.
        """
        if path is None:
            stamp = datetime.now(UTC).strftime("%Y%m%d-%H%M%S")
            path = journals_dir() / f"{stamp}-{self.profile}.json"
        if merge and path.exists():
            self._merge_from(Journal.load(path))
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(f".{path.name}.tmp")
        tmp.write_text(json.dumps(self.to_dict(), indent=2) + "\n", encoding="utf-8")
        os.replace(tmp, path)
        self.path = path
        return path

    def _merge_from(self, older: Journal) -> None:
        mine = {e.package for e in self.entries}
        self.entries = [e for e in older.entries if e.package not in mine] + self.entries
        if not mine:
            self.created_at = older.created_at or self.created_at
        self.notes = (older.notes + [n for n in self.notes if n not in older.notes])[-20:]

    def mark_restored(self, packages: list[str]) -> None:
        """Record a restore so drift checks stop treating these as wanted-disabled."""
        if not packages or self.path is None:
            return
        now = datetime.now(UTC).isoformat(timespec="seconds")
        for entry in self.entries:
            if entry.package in packages:
                entry.restored_at = now
        self.save(self.path)

    def journaled_packages(self) -> set[str]:
        return {e.package for e in self.entries if e.action == "disable-user"}

    @classmethod
    def load(cls, path: Path) -> Journal:
        data = json.loads(path.read_text(encoding="utf-8"))
        if "host" not in data:
            raise ValueError(
                f"{path.name} is not a disable journal (missing host); "
                "layout/cover files must not be loaded as journals"
            )
        known = {f.name for f in fields(JournalEntry)}
        entries = [
            JournalEntry(**{k: v for k, v in e.items() if k in known})
            for e in data.get("entries", [])
        ]
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


def is_disable_journal(path: Path) -> bool:
    """True if path looks like a disable/restore journal (not layout/snapshot)."""
    name = path.name
    if not name.endswith(".json"):
        return False
    if name.startswith("snapshot-"):
        return False
    return name not in _NON_JOURNAL_NAMES


def iter_journals() -> list[Path]:
    """Disable/restore journals only (excludes snapshots and home-layout)."""
    return sorted(p for p in journals_dir().glob("*.json") if is_disable_journal(p))


def _created_at(path: Path) -> str:
    try:
        return str(json.loads(path.read_text(encoding="utf-8")).get("created_at") or "")
    except (OSError, ValueError):
        return ""


def latest_journal(profile: str | None = None) -> Path | None:
    """Most recent disable run by ``created_at``, not by filename.

    Sorting names put ``screensaver.json`` and ``home-clean-handover.json`` above every
    timestamped journal, so a bare ``restore`` picked one of those.
    """
    files = [p for p in iter_journals() if profile is None or profile in p.stem]
    if not files:
        return None
    return max(files, key=lambda p: (_created_at(p), p.name))


def journaled_packages() -> set[str]:
    """Every package any journal recorded as disabled by Buckler."""
    out: set[str] = set()
    for path in iter_journals():
        try:
            out |= Journal.load(path).journaled_packages()
        except (OSError, ValueError):
            continue
    return out


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
