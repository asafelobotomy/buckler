"""Load YAML package profiles from profiles/."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from shieldctl.paths import profiles_dir


@dataclass
class Profile:
    name: str
    description: str = ""
    packages: list[str] = field(default_factory=list)
    stock_launcher: str | None = None
    our_launcher: str | None = None
    path: Path | None = None


def _load_yaml(path: Path) -> dict[str, Any]:
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"Profile {path} must be a mapping")
    return data


def load_profile(name: str) -> Profile:
    path = profiles_dir() / f"{name}.yaml"
    if not path.is_file():
        raise FileNotFoundError(f"Unknown profile: {name} ({path})")
    data = _load_yaml(path)
    packages = data.get("packages") or []
    if not isinstance(packages, list):
        raise ValueError(f"packages in {path} must be a list")
    return Profile(
        name=str(data.get("name") or name),
        description=str(data.get("description") or ""),
        packages=[str(p) for p in packages],
        stock_launcher=data.get("stock_launcher"),
        our_launcher=data.get("our_launcher"),
        path=path,
    )


def load_never() -> set[str]:
    return set(load_profile("never").packages)


def list_profiles() -> list[str]:
    return sorted(p.stem for p in profiles_dir().glob("*.yaml") if p.stem != "never")
