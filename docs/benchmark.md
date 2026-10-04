# Benchmark Protocol

Repeatable before/after performance comparisons for Winlator on high-end
Snapdragon devices (primary target: Snapdragon 8 Elite / Adreno 830).

## Principles

- Compare only runs that share the same device, game scene, resolution,
  container, driver selection and thermal state (start cool, note battery %).
- Track **frame-time consistency, sustained performance and crashes** — not
  just peak FPS. A "faster" driver that stutters more is a regression.
- Every claim needs a CSV run from the frame-time logger plus a copy of the
  Graphics Diagnostics report.

## Frame-time logger

Enable **Settings → Frame-time logger** (also on in `DEBUG_MODE`). Each sandbox
run writes a CSV to `Downloads/Winlator/benchmarks/`:

```
frame,delta_ms,timestamp_ms
0,16.712,16.712
1,16.631,33.343
...
# total_frames,12345
```

Suggested analysis per run: mean/median delta, p95/p99 delta, count of frames
with `delta_ms > 2 × median` (stutters), and total frames (crash indicator:
unexpectedly low totals).

## Driver A/B matrix

| Workload | Candidate A | Candidate B |
|---|---|---|
| Vulkan | Turnip | Vortek |
| OpenGL (Zink) | Zink | Gladio |

- Test each available present mode per workload (`GPUHelper.VkPresentMode`,
  per-driver config) — keep settings configurable; do not introduce a global
  "fastest" mode.
- Log the effective cache id (shown in the container's driver summary and in
  the diagnostics report) with every run.

## Box64 presets and CPU cores

- Benchmark `Box64PresetManager` presets and `CPUListView` assignments
  per game.
- Prefer conservative defaults; expose experimental tuning per container or
  shortcut only (existing `ShortcutSettingsDialog` override pattern), so one
  game's optimization cannot destabilize others.

## Shader stutter and memory

- Measure shader compilation stutter, memory use, and performance after
  extended play (>= 30 minutes) before/after any driver change.
- Do **not** raise GPU heap limits or disable synchronization globally without
  evidence that it helps and stays stable.

## Compatibility smoke

After every driver or renderer update, run the checklist in
[`smoke-suite.md`](smoke-suite.md).
