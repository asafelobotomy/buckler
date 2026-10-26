# Device dogfood baseline

Use this after profile or launcher changes, and after SHIELD Experience OTAs.

## Baseline (this unit)

| Field | Value |
|---|---|
| Device | SHIELD TV Pro (`mdarcy`) |
| Host | `BUCKLER_HOST` / `SHIELD_HOST` (default `192.168.178.23:5555`) |
| HOME | `dev.solon.bucklerhome/.MainActivity` |
| Screensaver | `dev.solon.bucklerhome/.ClockDream` |
| Profiles | `privacy-core`, `speech`, `minimal`, `extras` + handover + screensaver |

Golden snapshots live under `journals/snapshot-*.json` (gitignored). Re-export after intentional changes:

```bash
buckler snapshot
buckler check
buckler doctor
```

## Quick pass

```bash
export BUCKLER_HOST=<IP>:5555
buckler probe
buckler audit
buckler check          # should report drift: none when healthy
buckler doctor         # HOME + AI hard-fail; sticky pkgs force-stopped
buckler launcher verify
buckler home write && buckler home push   # layout defaults → import on HOME resume
```

On TV: first-run picks cover layout + shelves; the pencil icon in the Home header (**Edit Home**) adjusts later.

### buckler-home 0.8.0 smoke

- Header: logo + clock with date and weather, Now Playing (only while playing), network / VPN icons, pencil / bag / gear icons, news ticker line
- Header gear → Buckler Settings (not only stock); stock Settings still reachable inside
- Default scheme is Buckler (teal / sage); changing scheme recolours the header mark and the app-list icon / banner
- Settings → Header: each element toggles on its own; Header: Off leaves only shelves, and ◀ on a shelf still offers Settings
- Settings → Screensaver → Now Playing with music playing (album art) and over a paused YouTube video (thumbnail, Paused)
- Settings → Screensaver: Preview 3D Maze, 3D Pipes, Mystify, Starfield (clock overlay toggles)
- Settings → Header: 12/24 h and date format apply to Home; set a city and weather appears; news source cycles
- Play music in any app: Now Playing shows title and prev / play-pause / next work (after media access is granted)
- Network icon opens network settings; VPN icon opens the VPN app
- Menu → Move: menu closes, ◀ ▶ slide the tile, OK places it (does not launch), Back cancels
- Settings → Screensaver: Preview each style (clock, DVD, online photos, My photos)
- Settings → Screensaver → Start after: presets, Custom… (step buttons) and Off apply (after `buckler launcher grant-settings`)
- Settings → Appearance: each colour scheme, font, layout and app size changes Home immediately
- Settings → Accessibility: high contrast, strong focus outline, text size, reduce motion, speak focused item
- ◀ on the first tile of a shelf: Move shelf (▲ ▼ / OK / Back), Hide shelf, Edit Home
- Long-press / Menu: Open, Change cover, Remove, Uninstall (user apps), Info
- Change cover works **without** linking accounts (icon / banner / curated)
- Settings → Covers: Connect SteamGridDB (QR) / Connect IGDB (device code when `TWITCH_CLIENT_ID` set)
- Refresh curated pack succeeds via **bundled asset** even if GitHub raw 404s (private repo)
- Update dialog appears when a newer GitHub release has an APK asset; Snooze / Dismiss work
- Release APK naming: prefer `buckler-home*.apk` on the GitHub Release
- `buckler check` / `snapshot` / `setup` succeed with `journals/home-layout.json` present
- `buckler dns status` prints mode + specifier

## Known sticky processes

Some NVIDIA packages stay running after `pm disable-user` (GameStream `nvgamecast`, OSC, app messaging). `apply` / `check --fix` / `doctor` call `am force-stop`; they may respawn until reboot. After a reboot they should stay down if still disabled.

## After profile edits

```bash
buckler apply --profile privacy-core
buckler apply --profile speech
buckler apply --profile minimal
buckler apply --profile extras
# or: buckler check --fix
buckler snapshot
```
