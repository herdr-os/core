# Terminal engine: libghostty-vt spike

**Question.** Can Ghostty's terminal emulator (libghostty-vt, the library under
[Ghostling](https://github.com/ghostty-org/ghostling)) run on the R1 as the engine for SPEC §9's
optional interactive terminal mode?

**Answer.** Yes, it builds, runs and is fast on the R1. Today's read-only terminal doesn't need
it: Herdr already gives the bridge the rendered screen. It becomes worth wiring in only when
interactive mode is chosen for v1 (SPEC §17.4).

## What was built

| Piece | Where |
|---|---|
| libghostty-vt for `aarch64-linux-android`, cross-compiled with Zig 0.16 (no NDK needed for the library) | `tools/build-ghostty-vt.sh` |
| JNI glue: create, write raw bytes, one flat `int[]` snapshot per frame, free | `app/src/main/cpp/herdrvt.c` |
| Kotlin wrapper and Canvas renderer | `herdr/vt/GhosttyVt.kt`, `herdr/vt/VtView.kt` |
| Spike screen with a TUI-style frame and a benchmark | `herdr/ui/VtSpikeActivity.kt` (not in menus; `adb shell am start -n dev.r1ptt/.herdr.ui.VtSpikeActivity` as root) |
| Glue tests against a host build of the same library | `GhosttyVtTest` (skipped unless the script has built `out/herdrvt-host`) |

Ghostty is pinned to `f64f4aca2c29b554d111b36c3d946a9bddd159ff`, the commit Ghostling builds
against. `libherdrvt.so` (1.8 MB, stripped) is checked in under `app/src/main/jniLibs/arm64-v8a/` so
ordinary builds and CI don't need Zig or the NDK; rerun the script to rebuild it.

## Measured on the R1 (2026-10-28, release build)

| | |
|---|---|
| Parse ~1 MB of colored output | 52 ms (about 20 MB/s) |
| Snapshot a 48×18 screen | about 1 ms |
| First draw of that screen | 54–61 ms (includes first layout) |
| APK size added | 1.8 MB |

Rendered correctly: 16, 256 and 24-bit color, bold, faint, italic, underline, reverse video,
absolute cursor positioning, screen clears, box drawing. OSC 52 (clipboard) and OSC 8 (hyperlinks)
produce no text and no action.

Bugs the spike found in its own glue, fixed and covered by tests: a stack overflow on cells with
more than 16 combining marks (now drawn as U+FFFD), and a "no background" sentinel of `-1` that
collided with opaque white (now `0`).

## What shipping interactive mode would still take

1. **A raw stream from the bridge.** Today the bridge sends sanitized lines. Interactive mode needs
   the pane's raw bytes over an authenticated stream, with OSC 52, OSC 8 and other unsupported
   sequences still filtered on the bridge (SPEC §9).
2. **Input.** Keys and resize events from the R1 to the pane, behind the reserved `terminal_write`
   scope, off by default, with a visible mode switch.
3. **Rendering upkeep.** Partial redraws using libghostty's dirty-row state, scrollback paging
   with the wheel, and wide-character cells.
4. **Library churn.** libghostty-vt's C API is young. Pin the commit and rerun `GhosttyVtTest` on
   every bump.

## Recommendation

Keep the read-only terminal as the v1 default. If interactive mode is wanted, build it on this
engine starting with item 1; the native piece is no longer the risk.
