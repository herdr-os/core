# Herdr OS acceptance

SPEC §16's matrix, with what proves each row today. **Automated** means a test in this repository
checks it. **On the R1** is what was observed on a physical Rabbit R1 (serial 919129A6916002456531,
LineageOS 21 GSI on Rabbit's kernel, Magisk, robotOS module) on 2026-10-28; see the session below.
**Still open** is what neither has settled.

| Test | Automated | On the R1 | Still open |
|---|---|---|---|
| Wheel through 10 agent cards | `HerdrBridgeTest`; `HerdStateTest` order | One detent = one card, both directions, via the real `och1970_holl_key` device; a burst of 9 detents landed exactly 9 cards on, no card opened | Finger feel on a 10-agent herd |
| Hold PTT while viewing diff | Speech is a draft only (`HerdrScreen`); `HerdrBridgeTest` checks the deck keeps push-to-talk after another screen pauses | A hold on the deck, and after leaving an agent screen, stays in Herdr: robotOS's Hermes home never comes forward (sampled during and after the hold) | Dictation itself: needs an OpenAI key on the R1 |
| Agent changes during diff review | `revision_changed` refusal | Approve after a host-side edit was refused (`review … rejected`), the R1 said the code changed and showed the new revision; approving that one was accepted | |
| Duplicate completion events | `HerdStateTest`; bridge test | | |
| Stream drop mid-terminal | Stale cards, reconnect with backoff | Bridge killed with the terminal open: footer went "Offline · captured <time>" within 5 s, kept the last output, returned to Live by itself within 25 s of restart | Wi-Fi radio off/on |
| Action submitted, ACK lost | `HerdrClientTest` | | |
| Device lost and revoked | Bridge test; `HerdrBridgeTest` | Revoked via the bridge CLI: the open Changes screen closed and the deck showed Not paired with no cards within 20 s | Revoke while asleep, then wake |
| Side button never approves | `guardApprovalReview` fence | On "Confirm answer", a tap and a 2 s hold of the real button changed nothing and sent nothing; a touch on Send answer was `decision … accepted` | |
| Multi-host duplicated agent names | Bridge and `HerdStateTest` | 2026-10-29, over Tailscale with no USB forwarding: Machines & pairing listed `cerebro` (2 agents) and `namek` (1 agent) as connected, and the deck read 3 / 3 with namek's Pi card labelled NAMEK | Two hosts running agents with the same name |
| Pocket sleep | Stream only while a Herdr screen is visible; Desk/Connected keep it open (`HerdrBridgeTest`) | On USB power with the screen off, stopping the bridge was noticed by the R1 in the background (Desk mode) | Overnight drain with Herdr as home, Pocket and Connected |
| Terminal escape and OSC payloads | Bridge sanitizer tests | | |
| Long diff / binary file | Explicit states per content type | Untracked file and hunk view render at 480×640 | Scrolling feel on a long hunk |
| Stock restoration drill | Upstream tooling only | | Required before any release |

## Rerunning on a device

`tools/herdr-device-check.py` repeats every "On the R1" row above against a provisioned R1 on USB:
it starts a fixture bridge, pairs through the R1's screens, drives taps plus the real wheel and
side button, checks the screen and the bridge's audit log, and leaves the R1 unpaired. It passed
26/26 on 2026-10-28, including Files, Explain this hunk and Desk mode.

## R1 session, 2026-10-28

Stock rabbitOS was unlocked through Rabbit's developer mode, then flashed with the upstream
procedure ([INSTALL.md](INSTALL.md)): calibration partitions backed up with mtkclient, LineageOS
21 GSI `20260614` (MD5 checked) on slot a, Magisk v30.7, `tools/provision.sh`. Slot b held an
unfinished rabbitOS OTA; the first flash pass targeted it and failed before writing `system`;
slot a, the last successfully booted slot, took the GSI.

The R1 reached a fixture bridge on the Mac through `adb reverse tcp:8787` and was driven with
`input tap` plus `sendevent` on the real wheel (`/dev/input/event2`) and side button
(`/dev/input/event0`) as root. Results were read with `uiautomator dump` (Herdr screens set
`FLAG_SECURE`, so `screencap` is blank) and the bridge's audit table.

QR pairing: `enroll --qr` rendered a code on the Mac's screen; the R1's camera read it from the
Scan QR code screen, the R1 asked to confirm the bridge address, and pairing went through
(`enroll … accepted` for the new device) with the stream Live.

Found and fixed on hardware:

- With Herdr as home nothing started `PttService`, so the side button was dead. Herdr screens now
  start it on resume, as `HomeActivity` did.
- Android Back from a diff, a decision confirmation or a draft review left the screen instead of
  stepping back; Back on the home deck closed it. Back now follows the header arrow, and the deck
  ignores it.
- Times from the bridge were shown in UTC; they are now local.
- Herdr screens gave up push-to-talk when paused, so with the deck in the background (screen off
  without a lock screen) a hold fell through to a Hermes turn. The deck now keeps the button while
  it exists and other Herdr screens hand it back. An earlier note here cited `turn_start` as
  evidence the button reached Herdr; that metric is also logged for dictation, so it proves
  neither. The check now watches for the Hermes home instead.

## M1 gate: real Herdr

The bridge was run against Herdr 0.9.5 on a Mac (2026-10-28) with a read-only device. It reported
the machine as connected with its Herdr version, and the one live Claude Code session with its
project, state and generation. The same day the physical R1 paired read-only with that bridge (over
`adb reverse`) and showed the real herd: machine `cerebro` connected with 2 agents, the working
Claude Code session for this repository on top with its live terminal title, and its terminal
screen Live in the right folder.

On 2026-10-29 a second host, `namek` (Linux, Herdr over Tailscale SSH), was added with `herdr
machine add` and the bridge ran as `serve --tailscale --machine namek`. The R1, with the Tailscale
app signed in to the same tailnet, paired with the bridge's tailnet address (no `adb reverse`) and
went Live with both machines connected and all three agents on the deck.

## Android emulator at R1 geometry

On 2026-10-28 the debug APK ran on an Android 14 arm64 emulator configured as 480×640 at 190 dpi,
against `herdr-bridge --fixtures` on the host (`http://10.0.2.2:8787`). Driven by touch through
`adb shell input` and read back with `uiautomator dump`:

- `HerdActivity` resolved as HOME after `set-home-activity`, as `tools/provision.sh` does.
- Pairing through the Machines & pairing form with a real one-time code, using the emulator's
  Android Keystore to seal the token. The link went Live with one machine and three agents.
- Decision: question, option, confirmation naming Claude on MacBook Pro, then "Got it." The bridge
  audit log shows `decision … accepted`.
- Delegation: draft, resolution to Codex on MacBook Pro (tests-qa), Send, then "Task started".
  Audit: `delegate … accepted`.
- Agent, Terminal (Live, sanitized output) and Changes (file list with base and revision).
- Approve this revision through its dialog. Audit: `review … accepted`.
- Revoking the device with the bridge CLI closed the open Changes screen and left the deck
  "Not paired" with no cards, within one keepalive.
- `screencap` returns an empty image on Herdr screens, because they set `FLAG_SECURE`.

An emulator is not an R1. It has no wheel, no side button, a different panel and different radios,
so the Pending column above is unchanged.

## Screens

`HerdrBridgeTest` renders every Herdr screen at 480×640 and 190 dpi from live fixture data into
`app/build/ui-snapshots/herdr/`. These images show layout. They are not evidence of physical size,
touch accuracy or legibility on the panel.

## Before calling a build R1-ready

1. Flash a test R1 with the upstream procedure ([INSTALL.md](INSTALL.md)) and the Herdr APK.
2. Confirm the deck is the home screen after boot and after `HomeActivity` is opened from the menu.
3. Walk SPEC §7's four journeys against a real bridge with two hosts.
4. Fill the Pending column above with dates and observations.
