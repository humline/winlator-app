# Compatibility Smoke Suite

Run this checklist after every driver or renderer update (and before any
release). Record device, driver versions and results in the release notes
([`release-notes-template.md`](release-notes-template.md)).

## Core launch

- [ ] Cold start of the app: containers and shortcuts load, no crash loop.
- [ ] Launch a container to the desktop: first window appears, preloader closes.
- [ ] Launch a game/app shortcut directly (no desktop shown first).
- [ ] Repeated launches: stop the container, launch again 3x in a row.

## Gameplay

- [ ] 10+ minutes of active gameplay in the reference title.
- [ ] Resolution change (windowed → fullscreen and back).
- [ ] Suspend/resume the app (Home button, then return); audio and input recover.
- [ ] External mouse + keyboard: system cursor hides in-game and returns after exit.

## Graphics stack

- [ ] Vulkan workload with Turnip and with Vortek (both reach the menu).
- [ ] OpenGL workload with Zink and with Gladio (both reach the menu).
- [ ] Graphics Diagnostics report opens and copies to the clipboard.
- [ ] Container driver summary shows the expected package versions/cache id.

## Update safety

- [ ] Rootfs update (reinstall system files): user container data survives.
- [ ] Kill the app mid-update (optional, destructive test device only): next
      start recovers or rolls back to the previous system.
- [ ] Export a container backup (`.wcb`), delete the container, restore it:
      everything returns under a new container id, nothing is overwritten.
- [ ] Corrupt a restored archive's data on purpose: restore is refused with a
      checksum error and existing data is untouched.

## Optional

- [ ] Frame-time logger CSV appears in `Downloads/Winlator/benchmarks/` and
      matches the run (see [`benchmark.md`](benchmark.md)).
