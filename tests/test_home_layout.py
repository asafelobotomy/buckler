import pytest

from buckler.home_layout import (
    HomeLayoutError,
    default_home_layout,
    validate_home_layout,
    write_home_layout,
)


def test_default_validates() -> None:
    validate_home_layout(default_home_layout())
    validate_home_layout(default_home_layout(layout="poster"))


def test_rejects_bad_layout() -> None:
    doc = default_home_layout()
    doc["layout"] = "grid"
    with pytest.raises(HomeLayoutError, match="layout"):
        validate_home_layout(doc)


def test_write_roundtrip(tmp_path) -> None:
    path = write_home_layout(tmp_path / "home-layout.json")
    text = path.read_text(encoding="utf-8")
    assert '"schema": 1' in text
    assert "watch_next" in text
