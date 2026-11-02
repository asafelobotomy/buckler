#!/usr/bin/env python3
"""Claude Code PreToolUse hook: keep sessions in this repo off bucklerStore's code.

Files listed in .claude/store-owned.json belong to bucklerStore items (opt-in widgets and
screensavers). A session whose project is this repo may read them but not change them, nor the
list and this guard; that work is done by a session in ~/Projects/bucklerStore, which does not
load this repo's hooks. Exit 2 blocks the tool call and shows Claude the reason.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# The main checkout, which holds every worktree of it under .claude/worktrees/.
REPO = Path(str(ROOT).split("/.claude/worktrees/")[0])
OWNED = ROOT / ".claude" / "store-owned.json"
# The guard itself: changing these would switch it off.
GUARD = (".claude/store-owned.json", ".claude/hooks/store_guard.py", ".claude/settings.json")
# Shell commands that change files. Reading (cat, grep, git diff, the build) is never blocked.
WRITES = re.compile(
    r"sed\s+-[a-zA-Z]*i|perl\s+-[a-zA-Z]*i|\btee\b|\bcp\b|\bmv\b|\brm\b|\btruncate\b|\bpatch\b"
    r"|git\s+(apply|checkout|restore|reset|am|cherry-pick|revert|stash|mv|rm)\b"
    r"|write_text|write_bytes|open\([^)]*['\"][wa]"
)


def owners() -> dict[str, list[str]]:
    """Path → the store items it belongs to."""
    items = json.loads(OWNED.read_text(encoding="utf-8")).get("items", {})
    out: dict[str, list[str]] = {}
    for item, paths in items.items():
        for path in paths:
            out.setdefault(path, []).append(item)
    for path in GUARD:
        out.setdefault(path, []).append("the store guard")
    return out


def owned_path(target: str, table: dict[str, list[str]], cwd: str = "") -> str | None:
    """The listed path [target] is, in this checkout or any worktree of it."""
    full = (Path(cwd or ROOT) / target).resolve()
    if not full.is_relative_to(REPO):
        return None
    norm = full.as_posix()
    for path in table:
        if norm == path or norm.endswith("/" + path):
            return path
    return None


def names(command: str, path: str) -> bool:
    """Whether [command] mentions [path]: by file name, or by whole path for the guard's own files
    (settings.json is a common name)."""
    if path in GUARD:
        # Bare, ./, or an absolute path inside this repo; not ~/.claude, the user's own settings.
        for prefix in re.findall(rf"(?:^|[\s'\"=])(\S*?){re.escape(path)}", command):
            if prefix in ("", "./") or (prefix.startswith("/") and Path(prefix).resolve().is_relative_to(REPO)):
                return True
        return False
    return re.search(rf"(^|[\s/'\"=]){re.escape(Path(path).name)}\b", command) is not None


def verdict(event: dict) -> str | None:
    """Why this tool call is refused, or None to let it run."""
    table = owners()
    tool = event.get("tool_name", "")
    data = event.get("tool_input") or {}
    hit = None
    if tool in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
        hit = owned_path(str(data.get("file_path") or data.get("notebook_path") or ""), table, event.get("cwd", ""))
    elif tool == "Bash":
        command = str(data.get("command", ""))
        named = [p for p in table if names(command, p)]
        # A redirect into a listed file, or any file-changing command that names one.
        redirected = [p for p in named if re.search(rf">>?\s*['\"]?\S*{re.escape(Path(p).name)}\b", command)]
        hit = next(iter(redirected or (named if WRITES.search(command) else [])), None)
    if hit is None:
        return None
    items = ", ".join(table[hit])
    return (
        f"{hit} belongs to bucklerStore ({items}), so sessions in the buckler repo may not change it. "
        "Read it if you need to, but leave it as it is. To change it, ask the user to start a session in "
        "~/Projects/bucklerStore (or offer one with start_session, cwd ~/Projects/bucklerStore), with a brief of "
        "the change; that session edits Buckler's copy in a worktree. See CLAUDE.md here and in bucklerStore."
    )


def main() -> int:
    try:
        event = json.load(sys.stdin)
    except json.JSONDecodeError:
        return 0
    reason = verdict(event)
    if reason is None:
        return 0
    print(reason, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
