# Herdr OS security model

Agents and credentials stay on trusted hosts. The R1 is a remote control that can be lost, so it
holds one revocable, scoped token and nothing that would let it act outside the bridge's rules.
Each claim below names the code that enforces it and the test that checks it.

## Trust boundaries

| Party | Holds | Never holds |
|---|---|---|
| Herdr host | Agents, source, model keys, Herdr socket | Nothing from the R1 except receipts |
| Bridge | Herdr socket access (local) or `herdr --machine` (remote), SQLite of hashes, receipts and events | Raw source at rest, transcripts, terminal history |
| R1 | A device token sealed with the Android Keystore; a bounded in-memory deck | Herdr admin access, model keys, SSH keys, `.env` contents |

## Enrollment and revocation

- The owner mints a one-time code on the bridge host (`enroll`). It is valid once, for 10 minutes,
  and only its hash is stored (`bridge/src/auth/devices.ts`). There is no SMS pairing.
- `enroll --qr` encodes only the bridge address and that one-time code. A scanned code never pairs
  by itself: the R1 shows the bridge address and waits for a tap, so a stray QR code can't send it
  to someone else's bridge.
- The claimed token is shown only to the claiming request. The bridge stores its hash and the R1
  seals it (`herdr-identity.enc` via `Sealed`, the same Keystore path upstream uses).
- `revoke <device_id>` takes effect on the next request and on open event streams, which receive
  `revoked` and close (`server.ts` `events`). The R1 then deletes its token and all cached cards
  (`Herdr.forget`). Verified end to end in `HerdrBridgeTest`, and by `revoked device can no
  longer read or mutate` in the bridge tests.
- Unpairing on the R1 deletes its copy. Revocation on the bridge is what makes a lost token useless.

## Authorization

- Scopes are checked on every route (`authorize`): `read`, `act`, `review`, `admin`. Writing to a
  terminal (`terminal_write`) is reserved and has no route. Tests: `read-only device cannot
  mutate`, and `a read-only R1 sees the herd but every action is refused` on the R1 side.
- A device can be limited to machines (`canAccessMachine`). Sessions on other machines return 404.
- Speech never authorizes anything. `/v1/intents/resolve` only reads. Dispatch is a separate
  request that the user triggers with a tap on a confirmation naming the agent, machine and project.

## Actions are exact, once, and honest

- Every mutation needs an `Idempotency-Key`. A repeated key returns the first receipt and does not
  dispatch again (`receipts.ts`).
- A lost acknowledgement is settled by looking the key up (`GET /v1/actions?idempotency_key=`),
  never by resending (`HerdrClient.act`; test `a lost acknowledgement is recovered from the
  receipt, never re-sent`). If the lookup fails too, the R1 says "delivery uncertain" and offers
  **Check status**.
- Actions carry `expected_generation`. An agent that restarted refuses work aimed at its previous
  run (`generation_changed`).
- Answers to a decision carry the question's evidence fingerprint. Right before sending the key,
  the bridge re-reads the live screen and sends only if the same question is still there
  (`decide`).
- Review approvals carry the revision fingerprint and are refused once the tree changes
  (`revision_changed`). Approval means "Herdr review acknowledgment". It never commits, pushes or
  merges.
- On the R1, approval and answer confirmations are touch-only. The side button is fenced with
  `guardApprovalReview` while a confirmation is open.

## What content may leave a host

- Terminal output is sanitized before it is sent. OSC (hyperlinks, clipboard), DCS, cursor
  movement and C0 controls are removed, and likely secrets are redacted with a visible marker
  (`terminal/sanitize.ts`). The R1 never executes or copies received text automatically.
- Paths that may hold secrets (`.env*`, keys, keystores, `credentials`, `.npmrc`, `.netrc`) are
  `withheld`. Their contents never leave the host, in diffs or listings (`diff/git.ts`).
- "Explain this output" sends at most 4000 redacted characters, only after a consent dialog that
  says how much is sent and to whom.
- The audit log records device, action, target and outcome, without message text or source.
- Desk and Connected mode notifications use `VISIBILITY_SECRET`: nothing about agents shows on the
  lock screen.

## Network

- The bridge refuses plain HTTP on a non-loopback interface unless told the interface is a
  private tunnel (`--private-network`). Otherwise it needs TLS.
- The R1 app allows cleartext for upstream's LAN and tailnet Hermes setups. Point it at a TLS or
  tailnet address.

## Known limits

- A revoked R1 that stays offline keeps whatever was on its screen until it next reaches the
  bridge. Herdr OS does not promise remote wipe.
- The ssh-machine transport inherits the bridge host's SSH trust. Remote diffs need a bridge on
  that host.
- Secret redaction is pattern-based and can miss novel formats. Withheld paths are the stronger
  guarantee.
