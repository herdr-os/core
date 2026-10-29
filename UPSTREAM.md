# Upstream

Herdr OS is a fork of [windoze95/robotOS](https://github.com/windoze95/robotOS) (MIT).

| | |
|---|---|
| Pinned upstream commit | `8c5ddb565d6130f2525094d50fb90d264c7c0f6d` |
| Upstream date | 2026-10-12, "Give provisioned R1s a one-line lock-screen clock (#64)" |
| How it was pinned | Every upstream blob at that commit was hashed against this tree. All 368 upstream files match except the Herdr changes listed below. |

Production images are built from this pin, never from a moving upstream branch.

## What Herdr changes in upstream files

Only these upstream files are modified. Everything else Herdr adds lives in new files.

| File | Change |
|---|---|
| `app/src/main/AndroidManifest.xml` | `herdr.ui.HerdActivity` takes the HOME/LAUNCHER filter; the Herdr screens are registered. `HomeActivity` (Hermes) stays exported and reachable from the deck menu. |
| `app/src/main/java/dev/r1ptt/App.kt` | Adds the `herdr` singleton. |
| `app/src/main/res/values/colors.xml` | Adds the agent-card identity colors. |
| `tools/provision.sh` | Sets `HerdActivity` as the home activity. |
| `README.md` | A Herdr OS introduction above robotOS's README. |
| `app/build.gradle.kts` | Adds ZXing core (QR decoding for Herdr pairing). |
| `NOTICE` | Adds Ghostty's libghostty-vt (MIT), compiled into the app's native library. |
| `tools/flash-gsi.sh` | Reads the active slot in fastbootd, after the wipe, instead of in the bootloader before it. On an R1 with an unfinished OTA the slot changed in between and the flash targeted a slot with no partitions. |

## What Herdr adds

`bridge/` (the companion bridge), `protocol/openapi.yaml`, `app/src/main/java/dev/r1ptt/herdr/`
(client, state, screens), `app/src/main/res/drawable/herdr_sheep.xml`, their tests, `SPEC.md`,
`design-reference/` and the Herdr docs.

## Compatibility identities kept

Package `dev.r1ptt`, Magisk module `r1ptt_system`, signing inputs `ROBOTOS_*`, and config file
names are upstream's. Renaming any of them changes the signing identity or breaks upgrades of
installed R1s. They stay until distribution and signing are settled (SPEC §17.5).

## Licenses and attribution

`LICENSE` (MIT) and `NOTICE` are upstream's and retained. Bundled fonts keep their OFL texts in
`licenses/`. Herdr adds Ghostty's libghostty-vt (MIT, `licenses/Ghostty-MIT.txt`) as native code, and ZXing's core library (Apache 2.0, a Gradle dependency) for QR pairing; the sheep mascot is original.

## Upgrading the pin

1. `git fetch` upstream and review its log since the pin.
2. Merge it, resolving conflicts only in the files above.
3. `tools/upstream-check.py <robotOS clone> <new commit>` must pass: only those files may differ.
4. Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` and `cd bridge && npm test`.
5. Update the pin above.
