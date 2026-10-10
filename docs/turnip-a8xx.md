# Turnip drivers for Adreno a8xx

Build with `scripts/build-turnip-a8xx.sh` (needs the Android NDK) or run the
**Build Turnip a8xx** GitHub workflow. Output: `out/turnip-a8xx.zip`
(adrenotools-format driver), installable via the app's driver import.

## Where Winlator Ludashi gets drivers (needs verification)

Network access to the Ludashi repo was blocked while preparing this change, so
this is unverified. Search results point to community driver repos used by
Winlator forks: `K11MCH1/AdrenoToolsDrivers` (releases, includes beta a8xx),
`K11MCH1/WinlatorTurnipDrivers` (archived, .wcp), and `DiskDVD/TurniptoolsA8XX`
(a8xx build scripts). Ludashi itself: `StevenMXZ/Winlator-Ludashi`. Check its
source for the exact download URLs before hard-coding any in this app.

## Scope note

Porting all Ludashi/bionic features (Turnip MTR 3.2.2-p, etc.) is a large
effort that needs to be done feature by feature; it is not included here.
