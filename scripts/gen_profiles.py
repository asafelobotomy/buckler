#!/usr/bin/env python3
"""Bundle the CLI's profiles and safety lists into the launcher, for its on-TV setup engine.

Writes `launcher/app/src/main/assets/shield_profiles.json` from profiles/*.yaml, the stock
launchers in policy.py and the grants in launcher_ops.py, so the TV and `buckler setup` apply
the same lists with the same never-touch rules. test_parity checks the file is up to date.

Usage: python scripts/gen_profiles.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "src"))

from buckler.launcher_ops import GRANTED_APPOPS, SECURE_SETTINGS_PERMISSION
from buckler.policy import KNOWN_STOCK_LAUNCHERS
from buckler.profiles import list_profiles, load_never, load_profile

OUT = ROOT / "launcher/app/src/main/assets/shield_profiles.json"


def build() -> dict:
    profiles = {}
    for name in list_profiles():
        if name in ("never", "home-clean"):
            continue
        profile = load_profile(name)
        profiles[name] = {"description": profile.description, "packages": list(profile.packages)}
    return {
        "generated_by": "scripts/gen_profiles.py",
        "profiles": profiles,
        "never": sorted(load_never()),
        "stock_launchers": sorted(KNOWN_STOCK_LAUNCHERS),
        "appops": list(GRANTED_APPOPS),
        "permissions": [SECURE_SETTINGS_PERMISSION],
    }


def render() -> str:
    return json.dumps(build(), indent=2) + "\n"


def main() -> None:
    OUT.write_text(render(), encoding="utf-8")
    print(OUT.relative_to(ROOT))


if __name__ == "__main__":
    main()
