# Setup / restore experience (planned original work)

## Can we clone and decompile NVIDIA’s setup / restore UI?

**Not as a product path.** The stock setup / welcome / registration packages (`tungsten.setupwraith`, `com.nvidia.shield.welcome`, `com.nvidia.shield.registration`, etc.) are proprietary NVIDIA/Google code. Decompiling them to ship a refactored derivative would copy their expression and is not something this project will do.

What *is* fine:

- Observe **behaviour** (screens, order of prompts, what ADB restore needs) for UX inspiration
- Build an **original** first-run and restore flow in `shieldctl` + `shieldhome`
- Keep using reversible `pm disable-user` journals instead of imaging proprietary APKs

## Recommended design (original)

A couch-friendly **Restore & Setup** mode owned by us:

1. **Host CLI wizard** (`shieldctl setup` / `shieldctl restore-wizard`)
   - Connect / probe
   - Offer profiles: privacy-core → speech → minimal → extras → home-clean
   - Install/update shieldhome + ClockDream screensaver
   - Apply journals; verify AI stack + Chromecast kept
   - Print “turn Network debugging off”

2. **On-TV first-run** (shieldhome)
   - One-time welcome: dock picker, confirm screensaver, link to Settings
   - No network permission; no telemetric “registration”

3. **Disaster restore**
   - `shieldctl restore --journal …` already re-enables packages
   - Optional: export a single “desired state” snapshot JSON for the unit

This stays MIT-clean, matches kitetvOS’s long-term “own launcher / own experience” goal, and avoids soft-brick risk from ripping system setup packages.
