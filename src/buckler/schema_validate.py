"""Validate desired-state.v1 snapshot documents (shared with kitetvOS)."""

from __future__ import annotations

from typing import Any

REQUIRED_TOP = ("schema", "policy", "profiles")
REQUIRED_POLICY = (
    "keep_ai_upscaling",
    "keep_chromecast",
    "disable_mics",
    "launcher",
    "screensaver",
)


class SchemaError(ValueError):
    """Raised when a desired-state document fails validation."""


def validate_desired_state(doc: dict[str, Any]) -> None:
    """Raise SchemaError if *doc* does not satisfy desired-state.v1 required shape."""
    if not isinstance(doc, dict):
        raise SchemaError("document must be an object")

    for key in REQUIRED_TOP:
        if key not in doc:
            raise SchemaError(f"missing required field: {key}")

    if doc.get("schema") != 1:
        raise SchemaError(f"schema must be 1, got {doc.get('schema')!r}")

    policy = doc["policy"]
    if not isinstance(policy, dict):
        raise SchemaError("policy must be an object")
    for key in REQUIRED_POLICY:
        if key not in policy:
            raise SchemaError(f"missing required policy field: {key}")
        if key.startswith("keep_") or key == "disable_mics":
            if not isinstance(policy[key], bool):
                raise SchemaError(f"policy.{key} must be a boolean")
        elif key in ("launcher", "screensaver") and (
            not isinstance(policy[key], str) or not policy[key]
        ):
            raise SchemaError(f"policy.{key} must be a non-empty string")

    profiles = doc["profiles"]
    if not isinstance(profiles, list) or not all(isinstance(p, str) for p in profiles):
        raise SchemaError("profiles must be an array of strings")

    runtime = doc.get("runtime")
    if runtime is not None and runtime not in ("stock-adb", "kitetvos"):
        raise SchemaError(f"runtime must be stock-adb or kitetvos, got {runtime!r}")

    for list_key in ("desired_disabled", "actually_disabled", "ai_problems"):
        if list_key in doc:
            val = doc[list_key]
            if not isinstance(val, list) or not all(isinstance(x, str) for x in val):
                raise SchemaError(f"{list_key} must be an array of strings")
