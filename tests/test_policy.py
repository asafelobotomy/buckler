from shieldctl.policy import filter_allowed
from shieldctl.profiles import load_never


def test_never_blocks_ai_stack() -> None:
    never = load_never()
    assert "com.nvidia.NvCPLSvc" in never
    allowed, blocked = filter_allowed(
        ["com.nvidia.stats", "com.nvidia.NvCPLSvc", "com.google.android.katniss"]
    )
    assert "com.nvidia.stats" in allowed
    assert "com.google.android.katniss" in allowed
    assert "com.nvidia.NvCPLSvc" in blocked
