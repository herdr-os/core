import { test } from "node:test";
import assert from "node:assert/strict";
import { claude, claudeMessages, codex, codexMessages, lines, opencode, opencodeMessages, PHOTO, pi, piMessages } from "../src/insight/parse.ts";
import { Insights, locate } from "../src/insight/host.ts";
import { readFileSync } from "node:fs";
import { bridge, key, sid } from "./helpers.ts";
import { localRun } from "../src/insight/host.ts";

const j = (...os: object[]) => lines(os.map((o) => JSON.stringify(o)).join("\n"));
const t = (n: number) => `2026-10-29T09:00:0${n}.000Z`;

test("Claude Code: model, context, reply, and a background command that later finishes", () => {
  const r = claude(j(
    { type: "assistant", timestamp: t(0), message: { model: "claude-opus-5-5", usage: { input_tokens: 2, cache_read_input_tokens: 900, cache_creation_input_tokens: 98 }, content: [
      { type: "text", text: "Running the device check." },
      { type: "tool_use", id: "tu_bash", name: "Bash", input: { command: "python3 check.py", description: "Run the device check", run_in_background: true } },
      { type: "tool_use", id: "tu_agent", name: "Agent", input: { description: "Review the diff", subagent_type: "reviewer" } },
    ] } },
    { type: "user", timestamp: t(1), message: { content: [
      { type: "tool_result", tool_use_id: "tu_bash", content: "Command running in background with ID: bx5." },
      { type: "tool_result", tool_use_id: "tu_agent", content: [{ type: "text", text: "Looks good." }] },
    ] } },
    { type: "queue-operation", operation: "enqueue", timestamp: t(2), content: "<task-notification>\n<task-id>bx5</task-id>\n<tool-use-id>tu_bash</tool-use-id>\n<status>completed</status>\n</task-notification>" },
  ));
  assert.equal(r.model, "claude-opus-5-5");
  assert.deepEqual(r.context, { used: 1000, window: null });
  assert.equal(r.last_reply?.text, "Running the device check.");
  assert.equal(r.step, "Agent: Review the diff", "what it is doing is its latest tool step, not the terminal title");
  assert.deepEqual(r.tasks.map((x) => [x.command, x.state, x.ended_at]), [["Run the device check", "done", t(2)]]);
  assert.deepEqual(r.subagents.map((x) => [x.description, x.type, x.state]), [["Review the diff", "reviewer", "done"]]);
});

test("Claude Code: a background command with no notice yet is still running, and a cut first line is ignored", () => {
  const r = claude(lines('ntent":"cut"}\n' + JSON.stringify({ type: "assistant", timestamp: t(0), message: { model: "m", content: [{ type: "tool_use", id: "x", name: "Bash", input: { command: "npm test", run_in_background: true } }] } })));
  assert.deepEqual(r.tasks.map((x) => x.state), ["running"]);
});

test("Claude Code: a usage-limit reply is reported as a limit, and clears on the next normal reply", () => {
  const hit = claude(j({ type: "assistant", timestamp: t(0), message: { model: "m", content: [{ type: "text", text: "Claude usage limit reached. Your limit resets at 2pm." }] } }));
  assert.match(hit.limit?.message ?? "", /usage limit/);
  const later = claude(j(
    { type: "assistant", timestamp: t(0), message: { model: "m", content: [{ type: "text", text: "Claude usage limit reached." }] } },
    { type: "assistant", timestamp: t(1), message: { model: "m", content: [{ type: "text", text: "Back to work." }] } },
  ));
  assert.equal(later.limit, null);
});

test("Pi: model and thinking level from changes, context from the last reply's usage", () => {
  const r = pi(j(
    { type: "model_change", timestamp: t(0), provider: "openai", modelId: "gpt-5.5" },
    { type: "thinking_level_change", timestamp: t(0), thinkingLevel: "medium" },
    { type: "message", timestamp: t(1), message: { role: "assistant", model: "deepseek-v4.1-flash", usage: { input: 600, cacheRead: 12000, cacheWrite: 0 }, content: [{ type: "text", text: "Done." }], stopReason: "stop" } },
  ));
  assert.equal(r.model, "deepseek-v4.1-flash");
  assert.equal(r.effort, "medium");
  assert.deepEqual(r.context, { used: 12600, window: null });
  assert.equal(r.last_reply?.text, "Done.");
});

test("Codex: model from the turn, context against the window, last agent message", () => {
  const r = codex(j(
    { type: "turn_context", timestamp: t(0), payload: { model: "gpt-6-luna" } },
    { type: "event_msg", timestamp: t(0), payload: { type: "task_started", model_context_window: 258400 } },
    { type: "event_msg", timestamp: t(1), payload: { type: "token_count", info: { last_token_usage: { input_tokens: 120688 }, model_context_window: 258400 } } },
    { type: "event_msg", timestamp: t(2), payload: { type: "task_complete", last_agent_message: "Audit complete." } },
  ));
  assert.equal(r.model, "gpt-6-luna");
  assert.deepEqual(r.context, { used: 120688, window: 258400 });
  assert.equal(r.last_reply?.text, "Audit complete.");
});

test("locate refuses handles that would escape into the shell or out of a harness folder", () => {
  assert.equal(locate("claude", "abc'; rm -rf ~"), null);
  assert.equal(locate("pi", "/etc/passwd"), null);
  assert.equal(locate("pi", "/home/d/.pi/agent/sessions/../../.ssh/id_ed25519"), null);
  assert.match(locate("pi", "/home/d/.pi/agent/sessions/--x--/1_a.jsonl") ?? "", /^tail -c \d+ '\/home/);
  assert.match(locate("claude", "884b91b6-c9ec") ?? "", /\.claude\/projects\/\*\/884b91b6-c9ec\.jsonl/);
  assert.equal(locate("gemini", "abc"), null);
});

test("GET /v1/sessions/{id}/insight reads the agent's session file on its machine", async () => {
  const scripts: string[] = [];
  const file = JSON.stringify({ type: "assistant", timestamp: t(0), message: { model: "claude-opus-5-5", usage: { input_tokens: 10 }, content: [{ type: "text", text: "On it." }] } });
  const insights = new Insights((m) => (m === "local" ? async (s) => (scripts.push(s), file) : null), () => "abc");
  const b = await bridge({ insights });
  try {
    const r = await b.call("GET", `/v1/sessions/${sid("w1:p1")}/insight`);
    assert.equal(r.status, 200);
    assert.equal(r.body.insight.model, "claude-opus-5-5");
    assert.equal(r.body.insight.harness, "claude");
    assert.equal(r.body.insight.last_reply.text, "On it.");
    assert.deepEqual(r.body.insight.messages.map((m: any) => [m.role, m.text]), [["agent", "On it."]]);
    await b.call("GET", `/v1/sessions/${sid("w1:p1")}/insight`);
    assert.equal(scripts.length, 1, "a second read within seconds is served from cache");
    assert.equal((await b.call("GET", `/v1/sessions/${sid("nope")}/insight`)).status, 404);
  } finally {
    await b.close();
  }
});

const pairs = (ms: { role: string; text: string }[]) => ms.map((m) => [m.role, m.text]);

test("Claude Code messages: your plain lines and its text, never tool calls, results or injected notices", () => {
  const ms = claudeMessages(j(
    { type: "user", timestamp: t(0), message: { content: "Port the terminal to Ghostty." } },
    { type: "assistant", timestamp: t(1), message: { content: [{ type: "tool_use", id: "x", name: "Bash", input: {} }] } },
    { type: "user", timestamp: t(2), message: { content: [{ type: "tool_result", tool_use_id: "x", content: "ok" }] } },
    { type: "user", timestamp: t(2), isMeta: true, message: { content: "Caveat: local commands" } },
    { type: "user", timestamp: t(2), message: { content: "<task-notification><task-id>b</task-id></task-notification>" } },
    { type: "assistant", timestamp: t(3), message: { content: [{ type: "text", text: "Switch it, or keep plain lines?" }] } },
    { type: "user", timestamp: t(4), message: { content: [{ type: "text", text: `${PHOTO} /w/.herdr-attachments/r1.jpg\n\nThis shows after install.` }] } },
  ));
  assert.deepEqual(pairs(ms), [["you", "Port the terminal to Ghostty."], ["agent", "Switch it, or keep plain lines?"], ["you", "This shows after install."]]);
  assert.equal(ms[2].photo, "/w/.herdr-attachments/r1.jpg");
  assert.equal(ms[0].photo, null);
});

test("Pi and Codex messages, capped to the last 30", () => {
  assert.deepEqual(pairs(piMessages(j(
    { type: "message", timestamp: t(0), message: { role: "user", content: "Hi" } },
    { type: "message", timestamp: t(1), message: { role: "toolResult", content: [{ type: "text", text: "noise" }] } },
    { type: "message", timestamp: t(2), message: { role: "assistant", content: [{ type: "thinking", thinking: "x" }, { type: "text", text: "Hello." }] } },
  ))), [["you", "Hi"], ["agent", "Hello."]]);
  const many = Array.from({ length: 40 }, (_, i) => ({ type: "event_msg", timestamp: t(0), payload: { type: i % 2 ? "agent_message" : "user_message", message: `m${i}` } }));
  const ms = codexMessages(j({ type: "response_item", payload: { type: "function_call" } }, ...many));
  assert.equal(ms.length, 30);
  assert.deepEqual(pairs(ms.slice(-2)), [["you", "m38"], ["agent", "m39"]]);
});

test("POST /v1/sessions/{id}/attachments saves the photo beside the project, tells the agent its path, and sends once per key", async () => {
  const b = await bridge({ insights: new Insights((m) => (m === "local" ? localRun : null), () => null) });
  const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3, 0xff, 0xd9]);
  const post = (body: Buffer, k: string, q = "?note=Right%20after%20install") =>
    fetch(`${b.base}/v1/sessions/${sid("w2:p1")}/attachments${q}`, { method: "POST", body, headers: { Authorization: `Bearer ${b.token}`, "Content-Type": "image/jpeg", "Idempotency-Key": k } }).then(async (r) => ({ status: r.status, body: (await r.json()) as any }));
  try {
    const k = key();
    const r = await post(jpeg, k);
    assert.equal(r.body.receipt.status, "accepted", JSON.stringify(r.body));
    const path: string = r.body.receipt.result.path;
    assert.ok(path.startsWith(b.repos["tests-qa"] + "/.herdr-attachments/"), path);
    assert.deepEqual(readFileSync(path), jpeg);
    assert.equal(readFileSync(b.repos["tests-qa"] + "/.herdr-attachments/.gitignore", "utf8"), "*\n");
    assert.deepEqual(b.mac.sent.at(-1), { pane: "w2:p1", kind: "prompt", value: `${PHOTO} ${path}\n\nRight after install` });
    assert.equal((await post(jpeg, k)).body.receipt.action_id, r.body.receipt.action_id);
    assert.equal(b.mac.sent.filter((x) => x.value.startsWith(PHOTO)).length, 1, "a retry is not a second send");
    assert.equal((await post(Buffer.from("not a photo"), key())).status, 415);
    assert.equal((await post(Buffer.alloc(5 * 1024 * 1024 + 1, 0xff), key())).status, 413);
  } finally {
    await b.close();
  }
});

test("attachments need a way onto the agent's machine", async () => {
  const b = await bridge();
  try {
    const r = await fetch(`${b.base}/v1/sessions/${sid("w2:p1")}/attachments`, { method: "POST", body: Buffer.from([0xff, 0xd8, 0xff, 0]), headers: { Authorization: `Bearer ${b.token}`, "Content-Type": "image/jpeg", "Idempotency-Key": key() } });
    const body = (await r.json()) as any;
    assert.equal(body.receipt.status, "rejected");
    assert.match(body.receipt.detail, /attachments_unavailable/);
  } finally {
    await b.close();
  }
});

test("OpenCode: model, effort and context from the last reply, task tools as subagents, your words and its replies", () => {
  const ls = j(
    { type: "oc", at: 1000, msg: { role: "user" }, parts: [{ type: "text", text: "Review the worker" }, { type: "text", text: "injected", synthetic: true }] },
    { type: "oc", at: 2000, msg: { role: "assistant", modelID: "gpt-6-astra", variant: "medium", tokens: { input: 900, cache: { read: 99000, write: 100 } } }, parts: [
      { type: "text", text: "Sending a reviewer." },
      { type: "tool", id: "call_1", status: "completed", description: "Review lifecycle", agent: "general", start: 2000, end: 5000 },
      { type: "tool", id: "call_2", status: "running", description: "Check the TUI", agent: "general", start: 3000, end: null },
    ] },
    { type: "oc", at: 6000, msg: { role: "assistant", modelID: "gpt-6-astra", error: { name: "APIError", data: { message: "Rate limit reached. Resets in 5 minutes." } } }, parts: [] },
  );
  const r = opencode(ls);
  assert.equal(r.model, "gpt-6-astra");
  assert.equal(r.effort, "medium");
  assert.deepEqual(r.context, { used: 100000, window: null });
  assert.deepEqual(r.subagents.map((x) => [x.description, x.state]), [["Review lifecycle", "done"], ["Check the TUI", "working"]]);
  assert.match(r.limit?.message ?? "", /Rate limit/);
  assert.deepEqual(opencodeMessages(ls).map((m) => [m.role, m.text]), [["you", "Review the worker"], ["agent", "Sending a reviewer."]]);
});

test("locate refuses an OpenCode folder that could break out of the SQL or the shell", () => {
  assert.equal(locate("opencode", "/tmp/x'; drop"), null);
  assert.equal(locate("opencode", "/tmp/$(rm)"), null);
  assert.equal(locate("opencode", "relative/dir"), null);
  assert.match(locate("opencode", "/Users/d/tmp/herdr-lab") ?? "", /directory='\/Users\/d\/tmp\/herdr-lab'/);
});
