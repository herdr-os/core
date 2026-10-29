# Herdr OS — Product & Engineering Specification

**Version:** 0.4  
**Status:** Implementation proposal  
**Target:** Rabbit R1  
**Upstream foundation:** [windoze95/robotOS](https://github.com/windoze95/robotOS) (MIT; preserve LICENSE and NOTICE)  
**Product statement:** A pocket-native, voice-first companion for remotely orchestrating and supervising Herdr-managed coding agents.

> **Product invariant:** Agents execute on computers, not on the R1. The R1 is the conversational, physical control surface for observing, directing, reviewing, and deciding.

## 1. Goals and non-goals

### Goals

1. Turn a Rabbit R1 into a dedicated Herdr appliance with no general-purpose Android navigation in the normal flow.
2. Browse agents across Claude Code, Codex, Pi, and future adapters, organized by machine and project.
3. Know what needs attention immediately: blocked requests, approvals, failures, completions.
4. Speak naturally to the herd or a selected agent; delegate tasks and respond to decisions.
5. Inspect real remote terminal sessions and review source diffs from the device.
6. Keep owner credentials and agent execution on trusted hosts; support a private, self-hosted installation.
7. Preserve reliable power, pairing, firmware recovery, and physical hardware behavior from robotOS wherever possible.

### Non-goals for v1

- Running local coding agents or LLM inference on the Rabbit.
- Full desktop IDE, complex source editing, or arbitrary shell command entry as the default UI.
- A cloud SaaS control plane or dependency on a Herdr-hosted account.
- Reimplementing the R1 kernel, camera stack, radio stack, voice button, or flashing pipeline.
- Guaranteeing live push notifications during low-power radio-off periods.
- Treating model-generated explanations as evidence of the actual diff or terminal state.

## 2. Upstream decision and boundaries

Fork robotOS; retain its Android, LineageOS 21 GSI, Rabbit kernel/vendor, Magisk and provision tooling. The repository currently describes a Kotlin Android launcher, an R1-specific Magisk module, device control and power policies, a self-hosted HTTPS workspace hub, paired-device management, and Hermes integration. **These are upstream-reported capabilities, not all independently hardware-verified for our fork.**

### Preserve / adapt / replace

| Area | Decision | Why |
| --- | --- | --- |
| Rabbit kernel and vendor partitions | Preserve | R1 input drivers/hardware compatibility |
| LineageOS 21 GSI + Magisk deployment | Preserve initially | Proven upstream installation path |
| Button/wheel mappings, screen sizing, audio capture | Preserve; extend carefully | Critical physical interface |
| Provision/flash/rollback tooling | Preserve | Recovery and repeatable installs |
| Battery/radio policy | Preserve; add explicit modes | 1,000 mAh battery constrains always-on UX |
| Native Kotlin app and tests | Fork | Direct platform leverage |
| Shared hub, enrollment, policy verification | Adapt through a narrow seam | Valuable device management primitives |
| Hermes agent-specific UI and execution logic | Replace primary path with Herdr provider | Herdr is the authority for coding sessions |
| Speech provider implementation | Wrap behind provider interface | OpenAI, compatible self-hosted speech later |
| Card deck, mascot, terminal, diff, actions | New | Herdr-specific experience |

Do not delete Hermes support at fork time. Isolate it behind a provider interface, retain relevant tests, and switch the default to `HerdrProvider`. Removing Hermes code is a separate cleanup only after parity is proven.

### Provenance and fork safety

- Pin the exact reviewed upstream Git SHA in `UPSTREAM.md`; never build production images from moving `main` tags.
- Retain the MIT `LICENSE`, `NOTICE`, and third-party attribution, including fonts.
- Record original signing identities, Android package IDs, migration needs, and any compatibility implications before renaming `dev.r1ptt` or `r1ptt_system`.
- Treat unlocking as irreversible warranty loss and flashing as potentially destructive. Back up device-specific calibration partitions via documented safe tooling, verify stock restore, and do not claim it is guaranteed.
- Inventory bundled endpoints and outbound telemetry; disable or replace unwanted integrations deliberately.

## 3. System architecture

```text
Rabbit R1
  Herdr Native UI (Kotlin, custom Compose or native view layer)
    ├── Card Deck & Agent Detail
    ├── Conversation / Voice Overlay
    ├── Terminal Reader & Interactive Terminal (advanced)
    ├── Diff / Review / Decision
    ├── Files, Machines, Activity, Settings
    └── Local cache + resilient outbox
  R1 Runtime (forked robotOS)
    ├── Side-button / wheel / screen / audio
    ├── SpeechCapture + TTS adapters
    ├── Pairing / lost-device policy gate
    └── Connectivity / power manager
          |
          | HTTPS + authenticated WebSocket/SSE over private network
          v
Herdr Companion Bridge (self-hosted; separate process)
    ├── Device enrollment, scoped credentials, revocation
    ├── Machine registry and aggregated agent index
    ├── Snapshot + resumable event feed
    ├── Intent resolver / confirmation policy
    ├── Voice intent routing (remote service, optional)
    ├── Session output and terminal attach proxy
    ├── Repository diff service (revision-bound)
    └── Action receipt ledger + audit log
          |
          +---- Herdr host A (MacBook): Claude Code, Codex
          +---- Herdr host B (server): Pi, Codex
          +---- Herdr host C (workstation): other supported agents
```

The exact Herdr control API and events are **to be confirmed against the installed Herdr version**. Names below are *proposed companion contracts*, not claims about existing Herdr endpoints. Implement a versioned `HerdrAdapter` to translate real local interfaces to these contracts.

### Deployment

- Default: bridge on one trusted computer or home server, with local Herdr adapters on machines that run agents.
- Network: HTTPS with device-scoped credentials; Tailscale/private routing preferred. R1 Android Tailscale support must be verified on hardware, otherwise use a securely reachable HTTPS gateway with strict authentication.
- No direct R1-to-each-agent socket access.
- The bridge may be containerized; adapters must have only the permissions necessary to inspect/control their local Herdr instance.
- Data store: SQLite for device registrations, event cursors, action receipts, and optional minimal cache; no default storage of raw transcripts or source files. Durable event feed may use a bounded append-only log.
- Multiple hosts must maintain stable IDs and machine-qualified session identities.

## 4. Product information architecture

1. **The Herd:** priority-ordered stack of agent cards.
2. **Agent:** current state, latest significant event, machine/project, quick navigation to Voice, Terminal, Changes, Activity.
3. **Decision:** focused request with exact origin, impact, and explicit confirmation.
4. **Terminal:** real session output; structured compact mode default and raw terminal advanced mode.
5. **Changes:** per-file and per-hunk Git review; revision-bound actions.
6. **Conversation:** ongoing contextual voice/text with herd or selected agent.
7. **Machines:** online/offline and connectivity status, grouping and selection.
8. **Activity:** signal timeline (task started, approval requested, PR opened, tests failed, committed, completed).
9. **Settings:** pairing, network, speech, privacy, power modes, updates, diagnostics.

### Priority semantics

Sort by actionable state: `approval_required` > `blocked` > `failed` > `completed_unseen` > `working` > `idle`. Priorities are computed server-side but remain visible and deterministic. Never erase the user-selected position while a session is being actively reviewed; queue movement until exit, except critical security alerts.

## 5. Design system — Rabbit-native, Herdr identity

**Reference:** Rabbit-style compact physical product: high-contrast black canvas, oversize editorial sans typography, playful saturated card surfaces, tactile stacked-card movement, fast transitions, minimal chrome. Interpret rather than clone proprietary assets or Rabbit logos.

- Background: near black (`#050505`). Text: warm white (`#F7F7F5`). Primary physical accent: vivid orange (`#FF4A00`), with theme tokens configurable.
- Agent cards: cyan, lemon, violet, coral, etc. Color distinguishes cards but never serves as the only identity/state channel. Agent names and glyphs remain mandatory.
- Labels: small and clear; state change text in plain language. Monospace only where a machine/path/terminal demands it.
- Screen composition: one dominant focal card/task at a time, generous breathing room, no tiny dashboard grid.
- Card radius: distinct, smooth corners; layered edges communicate depth rather than shadows everywhere.
- Terminal/diff: dark canvas with reserved monospaced output and legible line-height; avoid squashing desktop terminal dimensions.
- Minimum interactive target: approximately 48 dp, consistent with upstream R1 guidance; test actual physical size.
- Respect OS-level text scaling and reduced motion. Keep error/approval colors distinct from decorative card colors.

### Mascot specification

Original **wingless isometric sheep**: compact rounded-voxel off-white wool, small dark faceplate, asymmetrical white dot / orange cross facial marks, dark side ear, short square legs with restrained orange hoof accents. No wings, fins, jetpacks, antennae, or mechanical spacecraft parts. Recognizable at 24 px; provide monochrome silhouette and 3D-like/isometric illustration separately. On boot/idle, use restrained expressive animation, never misleading state transitions.

### Motion language

- Wheel rotates deck with physical continuity and a short settle animation.
- New session appears from behind deck; blocked agent rises to front with explicit notification label.
- Task dispatched: response folds into a card; confirmed delivery animates only after server receipt.
- Completion: short card bloom and settle, with visual reduced-motion fallback.
- Reconnection: card desaturates and reveals stale timestamp; do not fake a running state.
- Animations must remain responsive while asynchronous events arrive, and cannot intercept safety confirmations.

## 6. Hardware interaction contract

| Input | Default | Context-specific |
| --- | --- | --- |
| Side button hold | Push to talk | Dictation; never approval by held gesture |
| Side button release | Finish capture, submit or prepare draft | Confirmation state preserves ability to edit/cancel |
| Wheel | Move through deck/cards | Scroll transcript, terminal lines, hunks, choices |
| Tap card | Open details | Tap selected choice to inspect |
| Swipe | Navigate/back/dismiss noncritical notice | Do not dismiss pending approvals without explicit choice |
| Touch keyboard | Optional typed input | Terminal advanced mode only after explicit activation |

Physical button events must not inadvertently approve a mutation. When review or high-risk decisions are shown, approval requires an explicit on-screen confirmation with operation/revision details. Upstream gesture compatibility must be tested with hardware before locking the mapping.

## 7. User journeys

### A. Morning status

Wake device → The Herd shows 2 working, 1 blocked → wheel selects blocked Claude card → full-screen question → inspect context → speak answer or choose reviewed option → confirm → receipt shows accepted → return to deck. The device shows **pending** until the host acknowledges.

### B. Delegate

Hold push-to-talk → “Ask Codex on my MacBook to write regression tests for authentication” → intent resolver shows agent, machine, repo, instruction, side effects → confirm → new task ID returned → card appears with live status. If intent is ambiguous, request a choice instead of dispatching to the wrong session.

### C. Inspect a failure

Open Claude → Terminal → compact view shows last meaningful command and failure lines → wheel scrolls → “Explain this error” sends selected text + metadata with consent and size limits → agent response attached to current session context.

### D. Review changes

Open Codex → Changes → list modified files with stats → choose file → advance hunks via wheel → explanation per hunk → “Request tests for this” or “Approve patch” → final screen includes agent, repo, base/head commit SHA, file count, diff fingerprint, and action scope → approval binds to same revision. If code changed, invalidate the review.

## 8. Companion Bridge API (proposed)

### Authentication

Device enrollment uses one-time codes and device-scoped credentials. Exchange credentials over TLS, rotate/revoke on lost-device events, never put host Herdr admin tokens or model API keys on the R1. No SMS-based pairing as default. Restrict actions by device/owner/machine/session and log all mutations.

### Read API

```http
GET  /v1/me
GET  /v1/machines
GET  /v1/agents?machine_id=...
GET  /v1/sessions/{id}
GET  /v1/sessions/{id}/activity?cursor=...
GET  /v1/sessions/{id}/terminal/snapshot?max_lines=200
GET  /v1/sessions/{id}/changes?revision=...
GET  /v1/sessions/{id}/changes/{file_id}?revision=...
GET  /v1/events?after={cursor}          # SSE or WebSocket equivalent
```

### Actions

```http
POST /v1/sessions/{id}/messages
POST /v1/sessions/{id}/actions/stop
POST /v1/sessions/{id}/actions/respond
POST /v1/sessions/{id}/actions/review
POST /v1/tasks/delegate
POST /v1/approvals/{approval_id}/decision
```

Every mutating request includes `Idempotency-Key`, actor/device identity, action scope, and optional expected revision/session generation. Response is `{ action_id, status: "pending"|"accepted"|"rejected"|"unknown", ... }`. Confirm end state from an action receipt endpoint; never resubmit uncertain operations blindly.

### Event schema

```json
{
  "event_id": "evt_...",
  "cursor": "opaque-monotonic-cursor",
  "occurred_at": "2026-10-28T12:00:00Z",
  "machine_id": "machine_...",
  "session_id": "session_...",
  "generation": 4,
  "type": "agent.blocked",
  "payload": { "summary": "Migration decision required", "approval_id": "approval_..." }
}
```

Events include `agent.started`, `agent.progress`, `agent.blocked`, `agent.failed`, `agent.completed`, `terminal.output`, `changes.updated`, `approval.requested`, `approval.resolved`, `machine.connected`, `machine.disconnected`. Consumers process events idempotently, recover gaps from snapshots, and reject events from older session generations.

## 9. Terminal subsystem

### Compact terminal view (default)

- Shows real remote process output with timestamp, working directory, command label, exit state and selectable snippets.
- Preserves ANSI colors when safe, strips hostile control sequences in compact mode, and clearly marks omitted/redacted output.
- Lines are wrapped for legibility. Maintain server-side bounded scrollback with cursor pagination and client-side ring buffer.
- A bottom indicator shows `Live`, `Paused`, `Offline`, or `Reconnecting`; do not silently display stale output as live.
- Wheel scrolls, touch selects output, long press offers Copy / Explain / Send to agent.

### Advanced terminal mode

- Optional PTY attach, true terminal emulator, Resize and input protocol; provide a minimal software keyboard when needed.
- Do not introduce an arbitrary shell via bridge; attach only to authorized Herdr session endpoints.
- Access to write/input requires an explicit capability and a visible mode switch. Read-only is default.
- Limit output bytes per second, bound memory, sanitize OSC hyperlinks/clipboard and unsupported escape sequences; no automatic execution of received text.
- Process restarts require a new stream generation and a clear reconnection boundary.

## 10. Diff subsystem

### Source of truth

Diffs come from Git on the trusted host and are associated with repository ID, base commit, head commit, and a stable fingerprint. An LLM summary is optional supplementary context, never source of truth.

### Rendering

- File list with changed line counts and status.
- One file at a time; one hunk at a time in focused view.
- Colored additions/deletions plus explicit `+` and `−` glyphs for accessibility.
- Long lines wrap visually, with a horizontal pan option to inspect exact whitespace.
- Binary, large, renamed, deleted, submodule and untracked files display explicit specialized states.
- Permission and secret filters apply before content is sent to the device.

### Review actions

`Explain hunk`, `Request changes`, `Approve reviewed revision`, `Open on host` (if supported). An approval must be bound to an immutable diff fingerprint and commit/revision, expire upon changes, and require an explicit scope confirmation. Approval means *Herdr review acknowledgment* unless explicitly wired to a real GitHub PR review or merge action; do not conflate the two.

## 11. Voice and conversation

- Prefer upstream PTT capture and input feedback; wrap speech-to-text and text-to-speech as configurable backends.
- Start with upstream provider implementations; design for self-hosted speech later, subject to latency/quality/battery tests.
- Two routes: **Herd** (resolve machine/session) and **Agent** (current session context). Always display destination before sending mutation.
- Speech transcripts remain drafts until accepted; explicit edit/cancel supported.
- Voice-generated actions are parsed into typed intents and validated by server-side policies; natural-language instructions never bypass authorization.
- Distinguish “answer my question” from “do this operation.” High-impact actions require review on screen, not audio-only assent.
- Redact secrets and avoid retaining raw microphone audio by default. User controls TTS, quiet mode, speech provider and retention.

## 12. Security, privacy, and trust

1. Device-scoped least-privilege authentication, private HTTPS, server-side action authorization.
2. Read and write capabilities separated; review and terminal mutation gated individually.
3. Never expose tokens, passwords, env-file contents, or raw agent credentials in terminal/diff responses; redact and provide a warning when content is omitted.
4. Locked/lost-device mode invalidates access and cached sensitive content; support revocation and recovery without claiming remote wipe is infallible.
5. Revalidate authorization on reconnect, during action execution, and before returning sensitive content.
6. Bind approvals to actor, device, session, request, and expected revision. Reject stale or duplicate approvals.
7. Handle uncertain network writes via idempotent receipt lookup. Never auto-retry destructive/externally visible actions unless the server proves safety.
8. Log security-relevant mutations without persistently capturing raw source, keys, or full conversation text.
9. Model output is untrusted: no model-generated tool call executes without structured validation and permission checks.
10. Signing and update channels must be controlled by the maintainer; keep a reproducible rollback path.

## 13. Power, offline and notifications

- **Pocket Mode (default):** upstream aggressive Wi-Fi/radio sleep; synchronize on wake; pending events may not arrive until next connection.
- **Connected Mode:** keep configured radios alive for push/SSE; show explicit warning about standby cost; measure actual drain before shipping claims.
- **Desk Mode:** plugged-in default; stable stream, speech and notifications where network supports it.
- On disconnect, retain a bounded redacted snapshot with `last synced` timestamp; mark all statuses stale.
- Queue only safe user drafts locally. High-risk actions are not silently queued for later execution.
- Event stream reconnect uses cursor + snapshot reconciliation. Duplicate events do not reorder cards repeatedly.
- Quiet mode suppresses speech/audio, not important visual events. No mandatory background polling in Pocket Mode.

## 14. Proposed repository structure

```text
herdr-os/
  UPSTREAM.md                  # source SHA, diffs, upgrade strategy
  LICENSE                      # retained upstream
  NOTICE                       # retained and augmented
  app/                         # robotOS Kotlin Android fork
    src/main/.../
      platform/                # preserved hardware + policies
      providers/               # AgentProvider, HerdrProvider, optional HermesProvider
      features/
        deck/
        agent/
        voice/
        terminal/
        diff/
        decisions/
        machines/
        activity/
        settings/
      design/                  # typography, motion, colors, mascot assets
  magisk/r1ptt-system/         # retained until compatibility migration
  tools/                       # upstream build/provision/flash scripts
  bridge/                      # self-hosted Herdr bridge
    src/
      api/
      auth/
      devices/
      herdr-adapter/
      events/
      terminal/
      diff/
      intents/
      receipts/
    tests/
  protocol/
    openapi.yaml
    schemas/
  deploy/
    compose.yaml
    examples/
  docs/
    INSTALL.md
    DESIGN.md
    SECURITY.md
    HARDWARE_ACCEPTANCE.md
    UPSTREAM_INTEGRATION.md
```

Bridge implementation choice: start in **TypeScript** for quick iteration and alignment with the Herdr integration environment, or **Rust** if measured resource needs justify it. The language is not a v0.4 requirement; the stable API and isolation boundaries are. Avoid two competing bridge implementations. Android remains Kotlin, matching upstream.

## 15. Delivery milestones

### M0 — Reproducible upstream R1 baseline

- Fork robotOS at pinned commit; pass upstream app/server tests.
- Build and sign local APK; retain compatibility package/module IDs.
- Test official unlock, backup, GSI installation, button/wheel, voice, screen, suspend, and full stock recovery on owned device.
- Record baseline standby drain, audio latency, and network reconnection.

**Gate:** known-good repeatable setup and recovery, with real-device evidence.

### M1 — Herdr Provider + Bridge

- Inspect actual Herdr host interfaces; implement `HerdrAdapter` and machine/session enumeration.
- Implement enrollment, scoped read token, agent snapshot and resumable event stream.
- Bind a Rabbit to real Herdr sessions and show state without simulated data.

**Gate:** device accurately distinguishes working, blocked, idle, failed, and disconnected for two hosts.

### M2 — Herdr deck and mascot

- Build deck navigation, agent detail, machine grouping, visual states and reduced-motion support.
- Integrate original wingless sheep mascot and boot/sleep states.
- Validate screen at actual 480×640 geometry, hardware wheel, tap targets, and responsiveness.

**Gate:** one-hand navigation without opening Android UI or using a tiny dashboard.

### M3 — Voice and safe delegation

- Connect PTT, context resolver, draft transcription, scoped dispatch and action receipts.
- Add approval state and idempotent recovery.

**Gate:** user can delegate and answer a blocker with zero accidental sends during interruption/reconnect tests.

### M4 — Terminal and diffs

- Stream remote session output, compact terminal, optional advanced mode.
- Add Git diff service, per-hunk view, review revision fingerprint and targeted agent feedback.

**Gate:** physical-device terminal and diff tasks work across scrolling, large output, binary files, network drop and revision changes.

### M5 — Hardening and distribution

- Test full battery modes, overnight standby, lost device, OTA or signed-app updates, upgrade/rollback and long-running agents.
- Document CI, security model, provider APIs and attribution.

**Gate:** reproducible signed release and recovery instructions, with no unsupported reliability claims.

## 16. Acceptance test matrix

| Test | Required outcome |
| --- | --- |
| Wheel through 10 agent cards | Stable selection, no input loss or accidental activation |
| Hold PTT while viewing diff | Speech captures; diff and review state stay intact |
| Agent changes during diff review | Approval invalidated; new revision clearly offered |
| Duplicate completion events | One state transition and one notification |
| Stream drop mid-terminal | Offline indicator, bounded buffering, safe reconnect |
| Action submitted, ACK lost | Receipt query recovers status without double dispatch |
| Device lost and revoked | Previously granted sessions cannot fetch sensitive data or mutate work |
| Multi-host duplicated agent names | IDs disambiguate machine/project/session |
| Pocket sleep | Clear stale state, no claim of always-live notifications |
| Terminal escape and OSC payloads | Unsafe sequences neutralized, no auto clipboard/hyperlinks |
| Long diff / binary file | Fast UI with explicit unsupported-content display |
| Stock restoration drill | Verified rescue path and recorded baseline |

## 17. Open decisions

1. Verify actual Herdr control interface and negotiate stable versioning (`HerdrAdapter` first).
2. Decide whether upstream hub becomes Herdr bridge or remains a separate device enrollment service; **default to a separate bridge and narrow reuse** until its Hermes coupling is mapped.
3. Choose production speech providers only after latency, privacy and battery measurements.
4. Decide whether true interactive terminal input ships in v1; read-only is mandatory, writable mode optional.
5. Establish distribution and signing before package/module renaming.
6. Test R1 private networking and OTA constraints on the exact firmware revision.

## 18. First engineering tickets

1. Pin upstream SHA and create `UPSTREAM.md` with license/NOTICE inventory.
2. Run `tools/build.sh` and upstream unit suites; capture CI and reproducibility notes.
3. Inventory Android activities, button/wheel handlers, speech service and device policy gate.
4. Map hub routes and remove direct Hermes assumptions behind `AgentProvider`.
5. Create versioned protocol types and OpenAPI for machines, sessions, events and receipts.
6. Implement read-only Herdr bridge and local mock fixtures.
7. Build physical-device deck prototype at 480×640, respecting real wheel navigation.
8. Implement terminal snapshot + tail cursor before any raw PTY writing.
9. Implement Git revision-bound diff data and invalidate-on-change behavior.
10. Demonstrate an end-to-end real session: blocked agent → inspect → reply → accepted receipt → resumed.

---

## Source references

- robotOS repository and README: https://github.com/windoze95/robotOS
- robotOS install: https://github.com/windoze95/robotOS/blob/main/docs/INSTALL.md
- robotOS daily driver: https://github.com/windoze95/robotOS/blob/main/docs/DAILY_DRIVER.md
- robotOS deployment: https://github.com/windoze95/robotOS/blob/main/docs/DEPLOYMENT.md

**Disclaimer:** This specification defines intended Herdr OS behavior. Existing robotOS implementation statements are based on upstream documentation; real-device acceptance must be independently reproduced. The proposed Herdr API is a design contract to implement after inspecting actual Herdr capabilities.
