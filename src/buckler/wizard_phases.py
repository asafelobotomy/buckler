"""Canonical multi-step wizard phases shared by stock CLI and kitetvOS docs."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Phase:
    id: str
    title: str
    summary: str


# Keep IDs stable — kitetvOS Provisioner should reuse them.
PHASES: tuple[Phase, ...] = (
    Phase("connect", "Connect", "Reach the Shield over Network debugging (stock) or skip on ROM."),
    Phase("probe", "Probe", "Read model, build, HOME, and AI-stack health."),
    Phase("privacy", "Privacy", "Disable telemetry, partner auto-installs, GameStream leftovers."),
    Phase("speech", "Voice & mics", "Disable Assistant / hotword / on-device speech (keep Chromecast)."),
    Phase("extras", "Extras", "Factory, SMB, OSC, welcome/registration leftovers."),
    Phase("home", "Home screen", "Install buckler-home and make it HOME."),
    Phase(
        "alternatives",
        "App alternatives",
        "Swap a stock app for an open one, or keep the stock app.",
    ),
    Phase(
        "suggestions",
        "Suggested apps",
        "Optional open apps. Nothing here replaces a stock app.",
    ),
    Phase("screensaver", "Screensaver", "ClockDream on; Backdrop off."),
    Phase("tune", "Tune", "Faster animations and speed-compile the launcher."),
    Phase("verify", "Verify", "Doctor: HOME, AI policy, Chromecast, sticky processes."),
    Phase("snapshot", "Snapshot", "Write desired-state.v1 JSON for OTAs / kitetvOS import."),
    Phase("finish", "Finish", "Summary and next steps (Network debugging off on stock)."),
)


def phase_index(phase_id: str) -> int:
    for i, p in enumerate(PHASES):
        if p.id == phase_id:
            return i
    raise KeyError(phase_id)


def banner(phase: Phase, index: int, total: int) -> str:
    bar = "█" * (index + 1) + "░" * (total - index - 1)
    return f"\n── [{index + 1}/{total}] {phase.title}  {bar}\n    {phase.summary}"


def banner_for(phase_id: str) -> str:
    index = phase_index(phase_id)
    return banner(PHASES[index], index, len(PHASES))
