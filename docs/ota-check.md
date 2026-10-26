# After a SHIELD Experience update

1. Re-enable Network debugging if it was off; note the Shield IP (reserve DHCP if possible).
2. Probe:

   ```bash
   cd ~/Projects/shieldctl
   source .venv/bin/activate   # or: uv run --python /usr/bin/python3
   shieldctl --host <IP>:5555 probe
   ```

3. Check drift against journals + `privacy-core` / `minimal`:

   ```bash
   shieldctl --host <IP>:5555 check
   ```

4. If packages came back enabled, re-apply:

   ```bash
   shieldctl --host <IP>:5555 check --fix
   # or explicitly:
   shieldctl --host <IP>:5555 apply --profile privacy-core
   shieldctl --host <IP>:5555 apply --profile minimal
   ```

5. Confirm HOME is still shieldhome:

   ```bash
   shieldctl --host <IP>:5555 launcher verify
   ```

   If stock returned as HOME, run `shieldctl launcher handover` again.

6. Confirm AI upscaling stack still reports OK in `probe` / `check`.
7. Turn Network debugging off.
