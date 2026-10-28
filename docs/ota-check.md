# After a SHIELD Experience update

1. Re-enable Network debugging if it was off; note the Shield IP (reserve DHCP if possible).
2. Probe:

   ```bash
   cd ~/Projects/buckler
   source .venv/bin/activate   # or: uv run --python /usr/bin/python3
   buckler --host <IP>:5555 probe
   ```

3. Check drift against what Buckler disabled: every journal entry, plus the full list of each setup profile you applied (`privacy-core`, `speech`, `minimal`, `extras`). Profiles you skipped and packages you restored since are left alone, and `--fix` only disables a stock launcher while buckler-home is HOME:

   ```bash
   buckler --host <IP>:5555 check
   ```

4. If packages came back enabled, re-apply:

   ```bash
   buckler --host <IP>:5555 check --fix
   # or explicitly:
   buckler --host <IP>:5555 apply --profile privacy-core
   buckler --host <IP>:5555 apply --profile speech
   buckler --host <IP>:5555 apply --profile minimal
   buckler --host <IP>:5555 apply --profile extras
   ```

5. Confirm HOME is still buckler-home:

   ```bash
   buckler --host <IP>:5555 launcher verify
   ```

   If stock returned as HOME, run `buckler launcher handover` again (disables all known stock launchers).

6. Confirm AI upscaling stack still reports OK in `probe` / `check`.
7. Turn Network debugging off.
