import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { claude, lines } from "../src/insight/parse.ts";
import { Insights, localRun, outputs } from "../src/insight/host.ts";
import type { Session } from "../src/protocol.ts";

const jl = (...os: object[]) => os.map((o) => JSON.stringify(o)).join("\n");
const t = (n: number) => `2026-10-29T09:00:0${n}.000Z`;

const session = jl(
  { type: "assistant", timestamp: t(0), message: { model: "claude-opus-5-5", content: [
    { type: "tool_use", id: "tu_agent", name: "Agent", input: { description: "Port JNI glue", subagent_type: "general-purpose", run_in_background: true } },
    { type: "tool_use", id: "tu_bash", name: "Bash", input: { description: "Run unit tests", command: "./gradlew test", run_in_background: true } },
    { type: "tool_use", id: "tu_lint", name: "Bash", input: { description: "Lint", command: "npm run lint", run_in_background: true } },
  ] } },
  { type: "user", timestamp: t(1), message: { content: [
    { type: "tool_result", tool_use_id: "tu_agent", content: [{ type: "text", text: "Async agent launched successfully.\nagentId: a1b2 (internal ID)" }] },
    { type: "tool_result", tool_use_id: "tu_bash", content: "Command running in background with ID: bx1. Output is being written to: OUT_BASH. You will be notified when it completes." },
    { type: "tool_result", tool_use_id: "tu_lint", content: "Command running in background with ID: bx2. Output is being written to: /etc/passwd.output. You will be notified." },
  ] } },
  { type: "queue-operation", operation: "enqueue", timestamp: t(3), content: "<task-notification>\n<task-id>bx2</task-id>\n<tool-use-id>tu_lint</tool-use-id>\n<status>failed</status>\n<summary>Background command \"Lint\" failed (exit code 1)</summary>\n</task-notification>" },
);
const meta = { agentType: "general-purpose", description: "Port JNI glue", toolUseId: "tu_agent" };
const transcript = jl(
  { type: "user", timestamp: t(1), message: { role: "user", content: "Port the glue." } },
  { type: "assistant", timestamp: t(2), message: { model: "claude-sonnet-5-5", usage: { input_tokens: 5, cache_read_input_tokens: 4000 }, content: [
    { type: "text", text: "Building now." },
    { type: "tool_use", id: "s1", name: "Bash", input: { command: "zig build\n-Demit-lib-vt", description: "Build libghostty-vt" } },
  ] } },
);

test("Claude Code: a subagent's own transcript gives its step, model, context and last write", () => {
  const r = claude(lines(session), [lines(jl(meta) + "\n" + transcript)]);
  assert.deepEqual(r.subagents.map((s) => [s.description, s.state, s.step, s.model, s.context_used, s.active_at]),
    [["Port JNI glue", "working", "Bash: Build libghostty-vt", "claude-sonnet-5-5", 4005, t(2)]]);
});

test("Claude Code: a failed background command keeps its exit code", () => {
  const r = claude(lines(session));
  assert.deepEqual(r.tasks.map((x) => [x.command, x.state, x.exit_code]), [["Run unit tests", "running", null], ["Lint", "failed", 1]]);
  assert.deepEqual(r.subagents[0].step, null, "no transcript, no step");
});

test("output tails are read only from Claude Code's own task folder", () => {
  assert.equal(outputs([{ id: "x", output_file: "/etc/passwd.output" } as any]), null);
  assert.equal(outputs([{ id: "x", output_file: "/tmp/claude-501/p/../../etc/tasks/x.output" } as any]), null);
  assert.match(outputs([{ id: "b'x", output_file: "/tmp/claude-501/-p/9f/tasks/bx1.output" } as any]) ?? "", /'bx'.*tail -c 4096 '\/tmp\/claude-501\/-p\/9f\/tasks\/bx1.output'/);
});

test("Insights reads subagent transcripts and command output on the agent's machine", async () => {
  const home = mkdtempSync(path.join(tmpdir(), "herdr-home-"));
  const outDir = path.join("/tmp", `claude-${process.getuid?.() ?? 0}`, `herdr-test-${process.pid}`, "s1", "tasks");
  try {
    mkdirSync(outDir, { recursive: true });
    const out = path.join(outDir, "bx1.output");
    writeFileSync(out, "> test\n\x1b[32m290 passed\x1b[0m\ntoken=abcdef123456\n291 / 417 passed · 0 failed\n\n");
    const dir = path.join(home, ".claude/projects/-work");
    mkdirSync(path.join(dir, "abc/subagents"), { recursive: true });
    writeFileSync(path.join(dir, "abc.jsonl"), session.replace("OUT_BASH", out) + "\n");
    writeFileSync(path.join(dir, "abc/subagents/agent-a1b2.meta.json"), JSON.stringify(meta));
    writeFileSync(path.join(dir, "abc/subagents/agent-a1b2.jsonl"), transcript + "\n");
    const insights = new Insights(() => (s) => localRun(`HOME='${home}'\n${s}`), () => "abc");
    const i = await insights.get({ session_id: "s", agent: "claude", machine_id: "local" } as Session);
    assert.equal(i?.subagents[0].step, "Bash: Build libghostty-vt");
    assert.deepEqual(i?.tasks[0].tail, ["290 passed", "token=[redacted]", "291 / 417 passed · 0 failed"]);
    assert.deepEqual(i?.tasks[1].tail, [], "a path outside the task folder is never read");
    assert.ok(!JSON.stringify(i).includes("output_file"), "host paths stay on the bridge");
  } finally {
    rmSync(home, { recursive: true, force: true });
    rmSync(path.dirname(path.dirname(outDir)), { recursive: true, force: true });
  }
});
