# Herdr OS: setup and use

Herdr OS turns a Rabbit R1 into a pocket remote for coding agents managed by
[Herdr](https://herdr.dev). Agents keep running on your computers. The R1
shows them as a deck of cards, tells you which one needs you, and lets you answer, delegate,
read the terminal and review diffs. Every action is confirmed on the R1's screen first.

```text
R1 (Herdr OS app)  ──HTTPS / tailnet──▶  herdr-bridge  ──Unix socket──▶  herdr (this Mac)
                                              └─ herdr --machine (SSH) ─▶  herdr (other hosts)
```

The R1 talks only to the bridge. It never holds Herdr admin access, model keys or SSH keys. The
contract between the two is [`protocol/openapi.yaml`](../protocol/openapi.yaml).

## 1. Run the bridge

On the computer that runs Herdr (Node 24 or newer, no dependencies to install):

```sh
cd bridge
node src/main.ts serve                       # local Herdr at ~/.config/herdr/herdr.sock
node src/main.ts serve --machine home-server # also a Herdr machine: `herdr --machine home-server …`
```

It listens on `127.0.0.1:8787`. To reach it from the R1, choose one:

- **Tailscale (preferred):** `--tailscale` serves on this machine's tailnet address. The R1 needs
  the Tailscale app signed in to the same tailnet (see below).
- **WireGuard or another tunnel:** `--host <tunnel IP> --private-network`.
- **TLS:** `--host 0.0.0.0 --tls-cert cert.pem --tls-key key.pem`.

The bridge refuses plain HTTP on any non-loopback address unless you pass `--private-network`.
State (device hashes, receipts, the event log, the audit log) is SQLite under
`~/.config/herdr-bridge/`. It stores no source, transcripts or terminal output.

To try everything without Herdr, `node src/main.ts serve --fixtures` serves the storyboard herd
(a blocked Claude, a working Codex, an idle Pi) backed by small real Git repositories. Pass
`--fixtures` to `enroll` and `revoke` as well, since fixtures use their own database.

### What the agent's own files add

Herdr reports each agent's state. For the model, context use, subagents, background commands and
usage limits, the bridge reads the agent's session file on its machine: Claude Code
(`~/.claude/projects/*/<id>.jsonl`), Codex (`~/.codex/sessions/`) and Pi (the path Herdr reports).
Remote machines are read over `ssh` to the target `herdr machine list` shows, so the bridge's user
needs the same key-based SSH that Herdr uses. It reads at most the last 3 MB of a file, at most
once every 4 seconds per agent. Other harnesses show no insight.

### Tailscale on the R1

Lineage on the R1 has no Play Store and no VPN consent screen. As root over USB:

```sh
adb install tailscale-android-universal-<version>.apk   # from pkgs.tailscale.com/stable/#android
adb shell appops set com.tailscale.ipn ACTIVATE_VPN allow
```

Open Tailscale, tap **Log in**, and open the login link on any computer:
`adb logcat -d | grep -o 'https://login.tailscale.com/a/[0-9a-f]*'`.

## 2. Pair the R1

```sh
node src/main.ts enroll                      # prints a one-time code, valid 10 minutes
node src/main.ts enroll --scopes read        # a look-but-don't-touch device
```

Fastest: print a QR code instead, with the address the R1 will use to reach the bridge.

```sh
node src/main.ts enroll --qr --tailscale     # address from Tailscale
node src/main.ts enroll --qr --url https://bridge.example.net
```

It renders in the terminal through `qrencode` (`brew install qrencode`); without it the
`herdr://pair?u=…&c=…` link is printed. On the R1, open **Machines & pairing** from the deck menu,
tap **Scan QR code**, point the camera at the code, and confirm the bridge address it shows. Or type
the bridge URL and the code and tap **Pair**. The QR code holds only the address and the one-time
code; it is as sensitive as the code itself and expires with it. The R1 seals its token with the Android Keystore and never shows it.

| Scope | Allows |
|---|---|
| `read` | Deck, sessions, activity, terminal, changes, intent resolution |
| `act` | Messages, delegation, stop, explain, answering decisions |
| `review` | Approving or requesting changes on a revision |

`node src/main.ts devices` lists devices. `node src/main.ts revoke <device_id>` revokes one
immediately: open streams end, and the R1 forgets its token and every cached card.

## 3. Use it

| Where | Wheel | Tap | Hold side button |
|---|---|---|---|
| The Herd | Next or previous card | Open the card (a blocked card opens its question) | Ask the herd: becomes a draft task |
| Agent | Scroll | Message, Terminal, Changes, Files, Stop | Tell this agent: becomes a draft message |
| Files | Scroll | Open a folder; a changed file opens its diff | Draft for this agent |
| Decision | Move between options | Choose, then **Send answer** on the confirmation | Ignored while a confirmation is open |
| Terminal | Scroll | Select text; ⓘ asks the agent to explain it | Draft for this agent |
| Changes | Next or previous hunk (in a file) | Open a file, explain a hunk, request changes, approve | Draft for this agent |

Speech is always a draft. The bridge resolves it to an agent, and you see the agent, machine and
project before **Send**. An ambiguous request ("ask Codex…" with two Codex agents) becomes a
choice. A question ("why is the build failing?") is never sent as an instruction.

The deck is ordered by what needs you: decision, blocked, failed, finished, working, idle. A card
you have open keeps its place until you leave it.

**Approve this revision** is a Herdr review acknowledgment of one exact Git revision. It does not
commit, push, merge or approve a pull request. If the agent changes the code after you looked,
the approval is refused and the new revision is offered.

## 4. Connectivity and power

Herdr follows SPEC §13's three modes, shown under **Machines & pairing → Power**:

| Mode | When | Behavior |
|---|---|---|
| Pocket | On battery (default) | Syncs only while a Herdr screen is on. Nothing is fetched with the screen off; cards show "Last known" and their sync age. |
| Desk | Plugged in | The stream stays open with the screen off, and an agent that needs you, fails or finishes posts a notification. |
| Connected | On battery, if **Stay connected on battery** is on | Same as Desk, and Wi-Fi is held up. Costs standby time. |

Notifications keep their content off the lock screen. Each sync starts with a snapshot and follows
the bridge's event stream from that cursor; reconnects back off from 1 s to 30 s.

### Spoken alerts

**Machines & pairing → Voice → Speak alerts aloud** makes the R1 say which agent needs you
("Claude Code on cerebro: Needs decision"), never what it asked. It is off by default because
anyone nearby hears it. It uses the R1's speech voice from `tools/r1ptt.json`. OpenAI's
`gpt-4o-mini-tts` is the default and the cheaper option. For ElevenLabs voices, point `tts` at it;
`voice` is an ElevenLabs voice ID and Flash v2.5 is its lowest-cost model:

```json
"tts": { "baseUrl": "https://api.elevenlabs.io/v1", "apiKey": "<xi-api-key>", "model": "eleven_flash_v2_5", "voice": "<voice id>" }
```

## 5. Where things live

| Path | What |
|---|---|
| `bridge/src/herdr-adapter/` | The only code that knows Herdr's socket API (protocol 22, herdr 0.9). Remote machines go through `herdr --machine`; diffs for them need a bridge on that host. |
| `bridge/src/index/herd.ts` | Machine and session index, state mapping, approvals, events |
| `bridge/src/api/server.ts` | HTTP API, scopes, receipts, SSE |
| `app/src/main/java/dev/r1ptt/herdr/` | R1 client: `HerdrClient`, `HerdState`, `Herdr`, screens in `ui/` |
| `protocol/openapi.yaml` | The contract; a bridge test fails if a route is undocumented |

Tests: `cd bridge && npm test`; `./gradlew :app:testDebugUnitTest` (JDK 17+). `HerdrBridgeTest`
starts the real bridge with fixtures, drives the R1 client through pairing, a decision, a
delegation, a revision-bound review and revocation, and renders each screen at 480×640 into
`app/build/ui-snapshots/herdr/`.

See also [DESIGN.md](DESIGN.md), [SECURITY.md](SECURITY.md),
[HARDWARE_ACCEPTANCE.md](HARDWARE_ACCEPTANCE.md) and [UPSTREAM.md](../UPSTREAM.md).
