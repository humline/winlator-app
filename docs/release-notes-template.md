# Release Notes — vX.Y (YYYY-MM-DD)

## Tested configuration

- Device / SoC / GPU: <e.g. Snapdragon 8 Elite / Adreno 830>
- Android version: <e.g. 15>
- Winlator version: <versionName>, rootfs: <rfsVersion>
- Driver packages: <e.g. turnip-26.2.0 + zink-22.2.5 (cache: ...)>
- Box64 / DXVK / VKD3D: <versions>
- Settings: <resolution, preset, present mode, other non-defaults>

## Changes

- <one line per user-visible change>

## Performance notes

- <frame-time summary vs previous release: mean/p95/p99, stutters, total frames>
- <attach or link the benchmark CSVs from Downloads/Winlator/benchmarks/>

## Known issues

- <issue + workaround if any>

## Backup / restore instructions

1. Before updating: open the container menu → **Export Backup** (or Settings →
   **Export Backup** for profiles/shortcuts). Archives are written to
   `Downloads/Winlator/backups/*.wcb` and include a SHA-256 manifest.
2. After updating: if something is broken, use the container menu / Settings →
   **Import Backup**. Restores verify checksums first and never overwrite
   existing containers (a fresh container id is allocated; conflicts are
   reported).
3. System files: Settings → **Reinstall System Files** uses the staged
   installer. If the first launch after an update fails, the app rolls back to
   the previous system automatically.

## Rollback units

- Driver changes are independent of rootfs/Wine changes (component versioning);
  a bad driver can be reverted by reinstalling the previous driver package
  without touching containers.
