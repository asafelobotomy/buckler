"""The guard that keeps sessions in this repo off bucklerStore's code (.claude/hooks/store_guard.py)."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

from buckler.store import load_index

ROOT = Path(__file__).resolve().parents[1]
HOOK = ROOT / ".claude" / "hooks" / "store_guard.py"
OWNED = json.loads((ROOT / ".claude" / "store-owned.json").read_text(encoding="utf-8"))["items"]
KT = "launcher/app/src/main/java/dev/solon/bucklerhome"


def run(tool: str, cwd: Path = ROOT, **tool_input: str) -> int:
    event = {"tool_name": tool, "tool_input": tool_input, "cwd": str(cwd)}
    result = subprocess.run([sys.executable, str(HOOK)], input=json.dumps(event), text=True, capture_output=True, check=False)
    return result.returncode


def test_every_opt_in_widget_and_screensaver_has_its_files_listed() -> None:
    opt_in = {i.id for i in load_index() if i.kind in ("widgets", "screensavers") and not i.raw["default"]}
    assert opt_in == set(OWNED)


def test_listed_files_exist_and_standard_ones_are_not_listed() -> None:
    listed = {path for paths in OWNED.values() for path in paths}
    for path in listed:
        assert (ROOT / path).is_file(), path
    for shared in ("MainStage.kt", "ScreensaverView.kt", "Palette.kt", "StoreLibrary.kt", "NowPlaying.kt"):
        assert f"{KT}/{shared}" not in listed


@pytest.mark.parametrize("path", [f"{KT}/MazeSaver.kt", f"{KT}/Companion.kt", ".claude/store-owned.json"])
def test_edits_to_store_files_are_blocked(path: str) -> None:
    assert run("Edit", file_path=str(ROOT / path)) == 2
    assert run("Write", file_path=path) == 2


def test_a_worktree_copy_is_blocked_too() -> None:
    assert run("Edit", file_path=str(ROOT / ".claude/worktrees/some-task" / KT / "PipesSaver.kt")) == 2


def test_buckler_files_and_other_repos_are_not_blocked() -> None:
    assert run("Edit", file_path=str(ROOT / KT / "MainStage.kt")) == 0
    assert run("Edit", file_path=str(Path.home() / ".claude" / "settings.json")) == 0
    assert run("Edit", file_path="/tmp/elsewhere/" + KT + "/MazeSaver.kt") == 0


@pytest.mark.parametrize("command", [
    f"sed -i 's/a/b/' {KT}/RetroSavers.kt",
    f"echo x > {KT}/DvdBounce.kt",
    f"git checkout -- {KT}/MazeGrid.kt",
    "python3 -c \"open('WeatherSaver.kt', 'w').write('')\"",
    "rm .claude/hooks/store_guard.py",
])
def test_shell_writes_to_store_files_are_blocked(command: str) -> None:
    assert run("Bash", command=command) == 2


@pytest.mark.parametrize("command", [
    f"grep -n Maze {KT}/MazeSaver.kt 2>/dev/null",
    f"git diff {KT}/MazeSaver.kt",
    f"sed -i 's/a/b/' {KT}/MainStage.kt",
    "cd launcher && ./gradlew :app:testDebugUnitTest",
    "cp ~/.claude/settings.json /tmp/x",
])
def test_reading_and_other_shell_work_is_not_blocked(command: str) -> None:
    assert run("Bash", command=command) == 0
