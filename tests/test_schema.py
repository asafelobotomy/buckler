import pytest

from buckler.policy import OUR_LAUNCHER
from buckler.schema_validate import SchemaError, validate_desired_state


def _valid_doc() -> dict:
    return {
        "schema": 1,
        "policy": {
            "keep_ai_upscaling": True,
            "keep_chromecast": True,
            "disable_mics": True,
            "launcher": OUR_LAUNCHER,
            "screensaver": f"{OUR_LAUNCHER}/.ClockDream",
        },
        "profiles": ["privacy-core", "speech"],
        "runtime": "stock-adb",
        "desired_disabled": ["com.google.android.katniss"],
    }


def test_validate_accepts_good_doc() -> None:
    validate_desired_state(_valid_doc())


def test_validate_rejects_wrong_schema() -> None:
    doc = _valid_doc()
    doc["schema"] = 2
    with pytest.raises(SchemaError, match="schema must be 1"):
        validate_desired_state(doc)


def test_validate_rejects_missing_policy_field() -> None:
    doc = _valid_doc()
    del doc["policy"]["launcher"]
    with pytest.raises(SchemaError, match="launcher"):
        validate_desired_state(doc)


def test_validate_rejects_bad_runtime() -> None:
    doc = _valid_doc()
    doc["runtime"] = "windows"
    with pytest.raises(SchemaError, match="runtime"):
        validate_desired_state(doc)
