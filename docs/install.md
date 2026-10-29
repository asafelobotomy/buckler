# Installing Buckler

Buckler is an ad-free Home screen for Android TV, with extra clean-up for the NVIDIA SHIELD. You
can install it from the TV alone, or from a computer.

Every release is on the [Releases page](https://github.com/asafelobotomy/buckler/releases). The
link below always gets the newest one:

```text
https://github.com/asafelobotomy/buckler/releases/latest/download/buckler-home.apk
```

## On the TV, no computer

1. Install **Downloader** (by AFTVnews) from the Play Store.
2. Open Downloader and enter the link above. When Android asks, allow Downloader to install
   apps, then choose **Install**.
3. Open Buckler from the apps row. Its setup wizard starts:
   - **Simple** asks for a colour scheme, a layout, the shelves and a screensaver. On a SHIELD it
     also has three clean-up switches (Privacy and Extras on, Voice & Assistant off).
   - **Advanced** adds the font, clean-up app by app, animation speed and Private DNS.
   - **Review** lists every change before anything is made. Nothing changes until you choose
     **Apply**.
4. The SHIELD changes (permissions, making Buckler the Home screen, turning apps off) need
   **Network debugging**, once:
   - Settings → Device Preferences → About: select **Build** seven times to show Developer
     options.
   - Settings → Device Preferences → Developer options: turn on **Network debugging**.
   - Back in the wizard, choose **Apply**. The TV asks whether to allow debugging: tick
     **Always allow** and choose **Allow**.
   - Buckler may restart once while it gets its permissions; the wizard comes back at Review.
     Choose **Apply** again to finish.
   - When the wizard is done you can turn Network debugging off again.

Run the wizard again any time from Settings → Setup → **Run setup wizard**. It starts from how
the TV is now, so it changes only what you change.

## From a computer

You need Python 3.12 or newer, [pipx](https://pipx.pypa.io/) and `adb` (Android
platform-tools). On the TV, turn on Network debugging as above, then:

```bash
pipx install git+https://github.com/asafelobotomy/buckler
buckler --host <tv-ip> probe      # the TV asks to allow the computer once
buckler setup --dry-run           # what setup would turn off, changing nothing
buckler setup --interactive       # asks before each step; installs the release APK
```

Without a source checkout, `buckler launcher install` and `setup` download the newest release's
`buckler-home.apk` and check it against its `.sha256` before installing. Each release also
carries the CLI as a wheel.

The TV's address is in Settings → Device Preferences → About → Status. `--host` is remembered,
so later commands don't need it.

## Undoing it

- **Turned-off apps:** Settings → Setup → **Restore everything** turns back on every app Buckler
  turned off from the TV. From a computer, `buckler restore-wizard --all` undoes everything
  `buckler` did.
- **Home screen:** Buckler never removes the stock launcher. Uninstall Buckler (Settings → Apps)
  and the TV goes back to it.

## Updates

Settings → Updates in Buckler checks GitHub for a newer release and installs it. An update must
be signed with the same key as the installed app; Android refuses any other.

## Safety

Buckler turns apps off; it never uninstalls them. A fixed list of apps is never touched,
including NVIDIA AI upscaling, Play Services, the remote and Bluetooth, Chromecast and Settings.
See [safety.md](safety.md).
