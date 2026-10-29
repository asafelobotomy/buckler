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

### buckler-home smoke

- Settings → Appearance → Header → Thin / Medium / Large scales the logo, clock, icons and Now Playing together
- Settings → Appearance → Header → News ticker: pick one source or several (world, gaming, business, tech). Several are interleaved, each story from the next source with that source's mark. Ticker speed Slow / Normal / Fast. No Hacker News and no Shuffle toggle
- Settings → Appearance → Main Stage → News changes every 30 / 60 / 90 seconds. OK on the News stage opens a reader with the article text
- OK on Now Playing opens the app that is playing. OK on Weather opens the weather app. OK on Suggested opens that title's app. The stage stays on screen while shelves scroll
- Header: logo + clock with date and weather, Now Playing (only while playing, and hidden while the stage is Now Playing), network / VPN icons (country code while VPN is up), pencil / bag / gear icons, news ticker line
- Header gear → Buckler Settings (not only stock); stock Settings still reachable inside
- Default scheme is Buckler (teal / sage); changing scheme recolours the header mark and the app-list icon / banner
- Settings → Appearance → Header: each element toggles on its own. Header: Off leaves the Main Stage and shelves. Edit Home and Settings are the header pencil and gear
- Settings → Screensaver → Now Playing with music playing (album art) and over a paused YouTube video (thumbnail, Paused)
- Settings → Screensaver: Preview 3D Maze, 3D Pipes, Mystify, Starfield (clock overlay toggles)
- Settings → Appearance → Header: 12/24 h and date format apply to Home; set a city and weather appears; chosen news sources or none apply
- Play music in any app: Now Playing shows title and prev / play-pause / next work (after media access is granted)
- Network icon opens network settings; VPN icon opens the VPN app
- Menu → Move: menu closes, ◀ ▶ slide the tile, OK places it (does not launch), Back cancels
- Settings → Screensaver → each screensaver: change an option (speed, size, count…), Preview shows it, and the other screensavers keep their own
- Settings → Screensaver → Start after: presets, Custom… (step buttons) and Off apply (after `buckler launcher grant-settings`)
- Settings → Appearance: each colour scheme, font, layout and app size changes Home immediately
- Settings → Accessibility: high contrast, strong focus outline, text size, reduce motion, speak focused item
- ◀ on the first tile of a shelf reveals the shelf button. OK opens Move shelf (▲ ▼ / OK / Back) and Hide shelf. Up Next also offers Sources (at most three cards per app)
- Long-press / Menu: Open, Change cover, Remove, Move, Pin to Apps, Uninstall (user apps), Info
- Change cover works **without** linking accounts (icon / banner / curated)
- Settings → Appearance → App Thumbnails: Connect SteamGridDB (QR) / Connect IGDB (device code when `TWITCH_CLIENT_ID` set)
- Refresh curated pack succeeds via **bundled asset** even if GitHub raw 404s (private repo)
- Update dialog appears when a newer GitHub release has an APK asset; Snooze / Dismiss work
- Release APK naming: prefer `buckler-home*.apk` on the GitHub Release
- `buckler check` / `snapshot` / `setup` succeed with `journals/home-layout.json` present
- `buckler dns status` prints mode + specifier
- Open an app, press Home, then open the recents view (≡) and press Home: each goes back to a clean Home at once (no recents cards left over it, no dropped keys); `adb logcat | grep "Launch timeout"` stays quiet
- Force-stop buckler-home on Home: the ticker shows the last headlines within a couple of seconds
- Settings → Clean up → Apps lists apps unopened for the chosen period (never Settings, Play Store, the update service or Files); Keep removes one; `buckler unused` prints the same list
- Settings → Remote: *Found* names the connected remote (2019: triangular); the drawing matches it; Netflix press / double / hold each run their choice (`adb logcat -s RemoteButtons`)
- Force-stop buckler-home with remote buttons on: Home says they stopped; Settings → Remote → *Turn on again* brings them back (`dumpsys accessibility` shows it bound)
- Settings → Clean up shows free space and app sizes; Cache and Largest apps list apps; *Clear Buckler's cache* drops it to a few kB; `buckler clean` reports MB freed
- Every layout (Icon / Poster / Banner) and app size (Thin to Large): the focused shelf sits whole under the stage, its title showing, the next one fading in; no tile cut at a shelf's edge
- Settings → Appearance → App Thumbnails: choose an icon pack; apps it lacks get icons drawn in its style (line packs) or its shape (full-colour packs); a newly installed app shows up and is styled without leaving Home
- Appearance shows colour schemes and fonts three to a row; picking Slate … Saffron changes the app-list icon to match

## Known sticky processes

Some NVIDIA packages stay running after `pm disable-user` (GameStream `nvgamecast`, OSC, app messaging): they are flagged PERSISTENT, and SHIELD starts persistent system apps at boot even when disabled, and even after `pm uninstall -k --user 0` (both tried on 2026-10-28; about 85 MB between them). `apply` / `check --fix` / `doctor` call `am force-stop`, which only lasts until they respawn. Freeing that memory for good needs root.

## After profile edits

```bash
buckler apply --profile privacy-core
buckler apply --profile speech
buckler apply --profile minimal
buckler apply --profile extras
# or: buckler check --fix
buckler snapshot
```
