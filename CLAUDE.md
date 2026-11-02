# Working on Buckler

## bucklerStore owns some of this repo's code

Opt-in widgets and screensavers are bucklerStore items: their code ships in Buckler, but it
belongs to [bucklerStore](https://github.com/asafelobotomy/bucklerStore) (`~/Projects/bucklerStore`).
`.claude/store-owned.json` lists those files by store id (Lyrics, Calendar, Weather map, Companion,
Multiview, Comics, 3D Maze, Rolling weather and the rest).

- **Don't change a store-owned file** from a session in this repo, by any means (Edit, Write, sed,
  scripts, git checkout or restore). `.claude/hooks/store_guard.py` blocks it. Reading is fine.
- **Don't change the guard**: `.claude/store-owned.json`, `.claude/hooks/store_guard.py` and
  `.claude/settings.json`. Those belong to bucklerStore too.
- **When a task needs a store-owned file changed**, stop that part and hand it over: tell the user
  it is bucklerStore's, and offer a session in `~/Projects/bucklerStore` (`start_session`, `cwd`
  set to it) with a brief of exactly what should change and why. Carry on with the rest.
- **Shared files stay Buckler's**: the Main Stage and screensaver dispatch (`MainStage.kt`,
  `ScreensaverView.kt`, `MainStageScenes.kt`), `MainStageGlance.kt`, `GlanceWeather.kt`,
  `MainStagePlayer.kt`, `JellyfinClient.kt`, the store's own code (`Store*.kt`, `SettingsStorePane.kt`,
  `src/buckler/store.py`) and `Palette.kt`, built-in schemes included. Change them as usual, but
  don't change a store item's behaviour through them.
- **The four standard widgets** (News, Now Playing, Weather, Suggested) and **four standard
  screensavers** (Drifting clock, Photos, My photos, Now Playing) are Buckler's own.
- **A new widget or screensaver** is a store item unless the user says it is standard. Its catalog
  entry, its files' entry in `.claude/store-owned.json` and the refreshed `index.json` copies come
  from a bucklerStore session.
