import { test } from "node:test";
import assert from "node:assert/strict";
import { writeFileSync, appendFileSync } from "node:fs";
import path from "node:path";
import { bridge, key, sid } from "./helpers.ts";

test("enrollment codes are single-use and tokens authenticate", async () => {
  const b = await bridge();
  try {
    assert.ok(b.token.startsWith("hdr_"));
    const me = await b.call("GET", "/v1/me");
    assert.equal(me.status, 200);
    assert.equal(me.body.device.name, "Test R1");
    const code = b.devices.mintCode();
    const first = await fetch(`${b.base}/v1/enrollments/claim`, { method: "POST", body: JSON.stringify({ code }) });
    const again = await fetch(`${b.base}/v1/enrollments/claim`, { method: "POST", body: JSON.stringify({ code }) });
    assert.equal(first.status, 200);
    assert.equal(again.status, 400);
    const anon = await fetch(`${b.base}/v1/agents`);
    assert.equal(anon.status, 401);
  } finally {
    await b.close();
  }
});

test("deck is sorted by actionable state, blocked Claude first", async () => {
  const b = await bridge();
  try {
    const { body } = await b.call("GET", "/v1/agents");
    assert.deepEqual(
      body.agents.map((a: any) => [a.agent, a.state]),
      [
        ["claude", "approval_required"],
        ["codex", "working"],
        ["pi", "idle"],
      ],
    );
    const claude = body.agents[0];
    assert.equal(claude.summary, "Which migration strategy should I use?");
    assert.equal(claude.project, "backend-api");
  } finally {
    await b.close();
  }
});

test("blocked agent decision: options parsed, answer bound to evidence, sent once", async () => {
  const b = await bridge();
  try {
    const s = await b.call("GET", `/v1/sessions/${sid("w1:p1")}`);
    const a = s.body.approval;
    assert.deepEqual(a.options.map((o: any) => o.label), ["Incremental migration", "Full replacement"]);
    assert.equal(a.parse, "structured");

    const k = key();
    const decision = { option_index: 1, evidence_fingerprint: a.evidence_fingerprint, expected_generation: 1 };
    const r1 = await b.act(`/v1/approvals/${a.approval_id}/decision`, decision, k);
    assert.equal(r1.body.receipt.status, "accepted");
    assert.equal(r1.body.receipt.result.chosen, "Incremental migration");
    // ACK lost → retry with same key returns the same receipt, no second keypress.
    const r2 = await b.act(`/v1/approvals/${a.approval_id}/decision`, decision, k);
    assert.equal(r2.body.receipt.action_id, r1.body.receipt.action_id);
    assert.deepEqual(b.mac.sent, [{ pane: "w1:p1", kind: "keys", value: "1" }]);
    const lookup = await b.call("GET", `/v1/actions?idempotency_key=${k}`);
    assert.equal(lookup.body.receipt.status, "accepted");
  } finally {
    await b.close();
  }
});

test("stale decision is rejected when the question changed on screen", async () => {
  const b = await bridge();
  try {
    const a = (await b.call("GET", `/v1/sessions/${sid("w1:p1")}`)).body.approval;
    b.mac.panes.get("w1:p1")!.terminal.push("│ Delete the users table? │", "│ ❯ 1. Yes │", "│   2. No │");
    const r = await b.act(`/v1/approvals/${a.approval_id}/decision`, { option_index: 1, evidence_fingerprint: a.evidence_fingerprint });
    assert.equal(r.body.receipt.status, "rejected");
    assert.match(r.body.receipt.detail, /approval_changed/);
    assert.deepEqual(b.mac.sent, []);
  } finally {
    await b.close();
  }
});

test("mutations require Idempotency-Key and reject reused keys with different bodies", async () => {
  const b = await bridge();
  try {
    const none = await b.call("POST", `/v1/sessions/${sid("w3:p1")}/messages`, { text: "hi" });
    assert.equal(none.status, 400);
    assert.equal(none.body.error.code, "idempotency_key_required");
    const k = key();
    await b.act(`/v1/sessions/${sid("w3:p1")}/messages`, { text: "one" }, k);
    const clash = await b.act(`/v1/sessions/${sid("w3:p1")}/messages`, { text: "two" }, k);
    assert.equal(clash.status, 409);
    assert.equal(b.mac.sent.length, 1);
  } finally {
    await b.close();
  }
});

test("messages to a blocked agent are rejected by Herdr, not silently queued", async () => {
  const b = await bridge();
  try {
    const r = await b.act(`/v1/sessions/${sid("w1:p1")}/messages`, { text: "hello" });
    assert.equal(r.body.receipt.status, "rejected");
    assert.match(r.body.receipt.detail, /agent_blocked/);
  } finally {
    await b.close();
  }
});

test("transport failure settles as unknown, never auto-retried", async () => {
  const b = await bridge();
  try {
    b.mac.online = false;
    const r = await b.act(`/v1/sessions/${sid("w3:p1")}/messages`, { text: "hello" });
    assert.equal(r.body.receipt.status, "unknown");
    assert.equal(b.mac.sent.length, 0);
  } finally {
    await b.close();
  }
});

test("delegate: resolve is read-only, ambiguous across hosts, then dispatch with receipt", async () => {
  const b = await bridge({ second: true });
  try {
    const amb = await b.call("POST", "/v1/intents/resolve", { text: "Ask Codex to write regression tests for authentication" });
    assert.equal(amb.body.status, "ambiguous");
    assert.equal(amb.body.candidates.length, 2);
    const one = await b.call("POST", "/v1/intents/resolve", { text: "Ask Codex on my MacBook to write regression tests for authentication" });
    assert.equal(one.body.status, "resolved");
    assert.equal(one.body.intent.session_id, "local/w2:p1");
    assert.equal(one.body.intent.instruction, "write regression tests for authentication");
    assert.equal(b.mac.sent.length, 0);
    const q = await b.call("POST", "/v1/intents/resolve", { text: "What is Codex working on?" });
    assert.equal(q.body.status, "not_an_operation");

    const r = await b.act("/v1/tasks/delegate", { session_id: one.body.intent.session_id, instruction: one.body.intent.instruction, expected_generation: 1 });
    assert.equal(r.body.receipt.status, "accepted");
    assert.ok(r.body.receipt.result.task_id);
    assert.deepEqual(b.mac.sent, [{ pane: "w2:p1", kind: "prompt", value: "write regression tests for authentication" }]);
  } finally {
    await b.close();
  }
});

test("multi-host duplicate pane ids are disambiguated by machine", async () => {
  const b = await bridge({ second: true });
  try {
    const ids = (await b.call("GET", "/v1/agents")).body.agents.map((a: any) => a.session_id);
    assert.ok(ids.includes("local/w1:p1"));
    assert.ok(ids.includes("ssh:home-server/w1:p1"));
    const machines = (await b.call("GET", "/v1/machines")).body.machines;
    assert.deepEqual(machines.map((m: any) => [m.label, m.status]), [["MacBook Pro", "connected"], ["Home Server", "connected"]]);
  } finally {
    await b.close();
  }
});

test("duplicate snapshots produce one transition and one notification", async () => {
  const b = await bridge();
  try {
    b.mac.set("w2:p1", { status: "done" });
    await b.herd.poll("local");
    await b.herd.poll("local");
    await b.herd.poll("local");
    const completed = b.events.recent(100).filter((e) => e.type === "agent.completed");
    assert.equal(completed.length, 1);
    assert.equal(b.herd.get("local/w2:p1")!.state, "completed_unseen");
    await b.call("GET", `/v1/sessions/${sid("w2:p1")}`);
    assert.equal(b.herd.get("local/w2:p1")!.state, "idle");
  } finally {
    await b.close();
  }
});

test("host drop marks sessions disconnected and stale; mutations refuse", async () => {
  const b = await bridge();
  try {
    b.mac.online = false;
    await b.herd.poll("local");
    const agents = (await b.call("GET", "/v1/agents")).body.agents;
    assert.ok(agents.every((a: any) => a.stale && a.state === "disconnected"));
    const r = await b.act(`/v1/sessions/${sid("w3:p1")}/messages`, { text: "hi" });
    assert.equal(r.body.receipt.status, "rejected");
    assert.match(r.body.receipt.detail, /machine_offline/);
    b.mac.online = true;
    await b.herd.poll("local");
    assert.equal(b.herd.get("local/w3:p1")!.stale, false);
  } finally {
    await b.close();
  }
});

test("agent restart bumps generation and voids actions bound to the old one", async () => {
  const b = await bridge();
  try {
    b.mac.set("w3:p1", { restart: true });
    await b.herd.poll("local");
    assert.equal(b.herd.get("local/w3:p1")!.generation, 2);
    const r = await b.act(`/v1/sessions/${sid("w3:p1")}/messages`, { text: "hi", expected_generation: 1 });
    assert.equal(r.body.receipt.status, "rejected");
    assert.match(r.body.receipt.detail, /generation_changed/);
  } finally {
    await b.close();
  }
});

test("changes: revision-bound review, invalidated when code changes", async () => {
  const b = await bridge();
  try {
    const ch = (await b.call("GET", `/v1/sessions/${sid("w1:p1")}/changes`)).body;
    assert.equal(ch.repo_name, "backend-api");
    assert.ok(ch.base_commit);
    assert.deepEqual(ch.files.map((f: any) => [f.path, f.status, f.additions, f.deletions]), [
      ["src/api/users.ts", "modified", 1, 1],
      ["src/db/schema.ts", "modified", 2, 1],
    ]);
    const users = ch.files[0];
    const fd = (await b.call("GET", `/v1/sessions/${sid("w1:p1")}/changes/${users.file_id}?revision=${ch.fingerprint}`)).body;
    const lines = fd.hunks[0].lines.filter((l: any) => l.kind !== "context").map((l: any) => [l.kind, l.text.trim()]);
    assert.deepEqual(lines, [
      ["del", "return res.status(200).json({"],
      ["add", "return res.status(201).json({"],
    ]);

    appendFileSync(path.join(b.repos["backend-api"], "src/api/users.ts"), "// sneaky\n");
    const stale = await b.act(`/v1/sessions/${sid("w1:p1")}/actions/review`, { revision: ch.fingerprint, decision: "approve" });
    assert.equal(stale.body.receipt.status, "rejected");
    assert.match(stale.body.receipt.detail, /revision_changed/);
    const staleFile = await b.call("GET", `/v1/sessions/${sid("w1:p1")}/changes/${users.file_id}?revision=${ch.fingerprint}`);
    assert.equal(staleFile.status, 409);

    const fresh = (await b.call("GET", `/v1/sessions/${sid("w1:p1")}/changes`)).body;
    assert.notEqual(fresh.fingerprint, ch.fingerprint);
    const ok = await b.act(`/v1/sessions/${sid("w1:p1")}/actions/review`, { revision: fresh.fingerprint, decision: "approve" });
    assert.equal(ok.body.receipt.status, "accepted");
    assert.equal(ok.body.receipt.result.meaning, "herdr_review_acknowledgment");
    assert.equal(ok.body.receipt.result.base_commit, ch.base_commit);
  } finally {
    await b.close();
  }
});

test("binary, untracked and sensitive files get explicit states; secrets never leave", async () => {
  const b = await bridge();
  try {
    const repo = b.repos["backend-api"];
    writeFileSync(path.join(repo, "logo.png"), Buffer.from([0x89, 0x50, 0x4e, 0x47, 0, 1, 2, 3]));
    writeFileSync(path.join(repo, ".env.local"), "OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuvwx\n");
    writeFileSync(path.join(repo, "notes.md"), "token = hunter2hunter2\n");
    const ch = (await b.call("GET", `/v1/sessions/${sid("w1:p1")}/changes`)).body;
    const by = Object.fromEntries(ch.files.map((f: any) => [f.path, f]));
    assert.equal(by["logo.png"].content, "binary");
    assert.equal(by[".env.local"].content, "withheld");
    assert.equal(by["notes.md"].status, "untracked");
    const env = (await b.call("GET", `/v1/sessions/${sid("w1:p1")}/changes/${by[".env.local"].file_id}`)).body;
    assert.deepEqual(env.hunks, []);
    const notes = (await b.call("GET", `/v1/sessions/${sid("w1:p1")}/changes/${by["notes.md"].file_id}`)).body;
    assert.equal(notes.hunks[0].lines[0].text, "token = [redacted]");
    assert.equal(notes.redactions, 1);
    const files = (await b.call("GET", `/v1/sessions/${sid("w1:p1")}/files`)).body;
    assert.ok(files.entries.find((e: any) => e.name === ".env").withheld);
    const escape = await b.call("GET", `/v1/sessions/${sid("w1:p1")}/files?path=../..`);
    assert.equal(escape.status, 403);
  } finally {
    await b.close();
  }
});

test("terminal snapshot neutralizes escapes and redacts secrets", async () => {
  const b = await bridge();
  try {
    b.mac.panes.get("w3:p1")!.terminal.push(
      "\u001b]52;c;cm0gLXJmIH4=\u0007clipboard",
      "\u001b]8;;https://evil.example\u0007click\u001b]8;;\u0007",
      "\u001b[31mFAIL\u001b[0m src/user.test.ts",
      "export ANTHROPIC_API_KEY=sk-ant-REDACTMEREDACTMEREDACTME",
    );
    const t = (await b.call("GET", `/v1/sessions/${sid("w3:p1")}/terminal/snapshot?max_lines=50`)).body;
    const texts = t.lines.map((l: any) => l.text);
    assert.ok(texts.includes("clipboard"));
    assert.ok(texts.includes("click"));
    assert.ok(!texts.join("\n").includes("evil.example"));
    assert.ok(!texts.join("\n").includes("sk-ant"));
    const fail = t.lines.find((l: any) => l.text.startsWith("FAIL"));
    assert.deepEqual(fail.spans, [{ start: 0, end: 4, fg: 1, bold: false }]);
    assert.ok(t.neutralized >= 3);
    assert.equal(t.redactions, 1);
    assert.equal(t.stream, "live");
  } finally {
    await b.close();
  }
});

test("revoked device can no longer read or mutate", async () => {
  const b = await bridge();
  try {
    assert.equal((await b.call("GET", "/v1/agents")).status, 200);
    b.devices.revoke(b.claim.device_id);
    assert.equal((await b.call("GET", "/v1/agents")).status, 401);
    assert.equal((await b.call("GET", `/v1/sessions/${sid("w3:p1")}/terminal/snapshot`)).status, 401);
    const r = await b.act(`/v1/sessions/${sid("w3:p1")}/messages`, { text: "hi" });
    assert.equal(r.status, 401);
    assert.equal(b.mac.sent.length, 0);
  } finally {
    await b.close();
  }
});

test("read-only device cannot mutate", async () => {
  const b = await bridge({ scopes: ["read"] });
  try {
    const r = await b.act(`/v1/sessions/${sid("w3:p1")}/messages`, { text: "hi" });
    assert.equal(r.status, 403);
    assert.equal(b.mac.sent.length, 0);
  } finally {
    await b.close();
  }
});

test("event feed is cursor-resumable and SSE replays after Last-Event-ID", async () => {
  const b = await bridge();
  try {
    const page = (await b.call("GET", "/v1/events")).body;
    assert.ok(page.events.length >= 4);
    const mid = page.events[1].cursor;
    const rest = (await b.call("GET", `/v1/events?after=${mid}`)).body;
    assert.equal(rest.events[0].cursor, page.events[2].cursor);

    const ctrl = new AbortController();
    const res = await fetch(`${b.base}/v1/events`, { headers: { Authorization: `Bearer ${b.token}`, Accept: "text/event-stream", "Last-Event-ID": page.cursor }, signal: ctrl.signal });
    const reader = res.body!.getReader();
    b.mac.set("w2:p1", { status: "blocked", terminal: ["Run rm -rf build?", "❯ 1. Yes", "  2. No"] });
    await b.herd.poll("local");
    let text = "";
    while (!text.includes("event: agent.blocked")) text += new TextDecoder().decode((await reader.read()).value);
    ctrl.abort();
    assert.match(text, /event: approval.requested/);
  } finally {
    await b.close();
  }
});
