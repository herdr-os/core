import type { Herd } from "../index/herd.ts";
import { localRun, type Run } from "./host.ts";

const ago = (s: number) => new Date(Date.now() - s * 1000).toISOString();
const jl = (...os: object[]) => os.map((o) => JSON.stringify(o)).join("\n");
const out = (id: string) => `/tmp/claude-501/-demo/f1/tasks/${id}.output`;

function claudeSession(): string {
  const use = (id: string, name: string, input: object) => ({ type: "tool_use", id, name, input });
  const result = (id: string, text: string) => ({ type: "tool_result", tool_use_id: id, content: text });
  const done = (id: string, status: string, summary: string) => ({ type: "queue-operation", operation: "enqueue", timestamp: ago(240),
    content: `<task-notification>\n<task-id>${id}</task-id>\n<tool-use-id>${id}</tool-use-id>\n<status>${status}</status>\n<summary>${summary}</summary>\n</task-notification>` });
  const main = jl(
    { type: "assistant", timestamp: ago(900), effort: "high", message: { model: "claude-opus-5-5", usage: { input_tokens: 40, cache_read_input_tokens: 109000 }, content: [
      { type: "text", text: "Splitting this across three helpers." },
      use("vt-porter", "Agent", { description: "Port JNI glue", subagent_type: "vt-porter", run_in_background: true }),
      use("test-runner", "Agent", { description: "Run GhosttyVtTest on R1", subagent_type: "test-runner", run_in_background: true }),
      use("doc-writer", "Agent", { description: "Write TERMINAL_ENGINE.md", subagent_type: "doc-writer", run_in_background: true }),
      use("gradle", "Bash", { description: "./gradlew testDebugUnitTest", command: "./gradlew testDebugUnitTest", run_in_background: true }),
      use("lint", "Bash", { description: "npm run lint", command: "npm run lint", run_in_background: true }),
    ] } },
    { type: "user", timestamp: ago(899), message: { content: [
      ...["vt-porter", "test-runner", "doc-writer"].map((id) => result(id, `Async agent launched successfully.\nagentId: ${id}`)),
      ...["gradle", "lint"].map((id) => result(id, `Command running in background with ID: ${id}. Output is being written to: ${out(id)}. You will be notified when it completes.`)),
    ] } },
    done("vt-porter", "completed", "Agent \"Port JNI glue\" completed"),
    done("lint", "failed", "Background command \"npm run lint\" failed (exit code 1)"),
  );
  const sub = (id: string, step: object, at: number) => `\n\u001e\n${JSON.stringify({ agentType: id, toolUseId: id })}\n` +
    jl({ type: "assistant", timestamp: ago(at), message: { model: "claude-sonnet-5-5", usage: { input_tokens: 12, cache_read_input_tokens: 38000 }, content: [step] } });
  return main + sub("vt-porter", { type: "text", text: "Ported the JNI glue; all 14 calls bound." }, 240)
    + sub("test-runner", use("t1", "Bash", { description: "Run GhosttyVtTest on the R1" }), 38)
    + sub("doc-writer", use("t2", "Write", { file_path: "docs/TERMINAL_ENGINE.md" }), 70);
}

const codexSession = () => jl(
  { type: "turn_context", timestamp: ago(600), payload: { model: "gpt-6-luna", effort: "high" } },
  { type: "event_msg", timestamp: ago(500), payload: { type: "token_count", info: { last_token_usage: { input_tokens: 64000 }, model_context_window: 258400 } } },
);

const outputs: Record<string, string> = {
  gradle: "> Task :app:testDebugUnitTest\n291 / 417 passed · 0 failed\n",
  lint: "src/a.ts 3:1 error no-unused-vars\nsrc/b.ts 9:4 error eqeqeq\n✖ 3 errors\n",
};

const taskOutputs = (script: string) =>
  Object.keys(outputs).filter((id) => script.includes(out(id))).map((id) => `\u001e${id}\n${outputs[id]}\n`).join("");

const at = (min: number) => new Date(Date.now() - min * 60_000).toISOString();
const jsonl = (...os: object[]) => os.map((o) => JSON.stringify(o)).join("\n");

/** Session files for the `--fixtures` herd, so the demo has a model, a conversation and a reply. */
const files = {
  claude: () => jsonl(
    { type: "user", timestamp: at(14), message: { role: "user", content: "The create-user test fails. Fix it, then add the users table migration." } },
    { type: "assistant", timestamp: at(12), message: { model: "claude-opus-5-5", usage: { input_tokens: 4, cache_read_input_tokens: 61000 }, content: [{ type: "text", text: "The route returns 200 where the test expects 201. Fixing the route first." }] } },
    { type: "user", timestamp: at(11), message: { role: "user", content: [{ type: "tool_result", tool_use_id: "t1", content: "ok" }] } },
    { type: "assistant", timestamp: at(2), message: { model: "claude-opus-5-5", usage: { input_tokens: 4, cache_read_input_tokens: 64000 }, content: [{ type: "text", text: "Test passes. For the migration: incremental, or replace the table in one go?" }] } },
  ),
  codex: () => jsonl(
    { type: "turn_context", timestamp: at(9), payload: { model: "gpt-6-luna", effort: "high" } },
    { type: "event_msg", timestamp: at(9), payload: { type: "user_message", message: "Write regression tests for the auth module." } },
    { type: "event_msg", timestamp: at(3), payload: { type: "agent_message", message: "Writing tests/auth.test.ts. 84 lines so far; running them next." } },
  ),
  pi: () => jsonl(
    { type: "message", timestamp: at(40), message: { role: "user", content: "Move the UI components to the new tokens." } },
    { type: "message", timestamp: at(31), message: { role: "assistant", model: "deepseek-v4.1-flash", content: [{ type: "text", text: "Done. 12 files changed and every screen builds." }], stopReason: "stop" } },
  ),
};

/**
 * Serves the canned session files, with whatever the R1 sent since appended as your turns, so the
 * demo thread answers back. Anything else (saving a photo) runs for real in the demo repos.
 */
export function fixtureInsights(herd: Herd) {
  const sessions = new Map<string, string>();
  const handle = (id: string) => {
    const h = id.replace(/[^A-Za-z0-9._-]/g, "_");
    sessions.set(h, id);
    return herd.get(id)?.agent === "pi" ? `/demo/.pi/agent/sessions/${h}.jsonl` : h;
  };
  const run: Run = async (script) => {
    if (script.includes("/tasks/")) return taskOutputs(script);
    if (!/^(tail -c|f=\$\(ls|f=')/.test(script)) return localRun(script);
    const id = [...sessions.keys()].find((h) => script.includes(h));
    const s = id ? herd.get(sessions.get(id)!) : null;
    if (!s) return "";
    const { adapter, paneId } = herd.adapterFor(s.session_id);
    const yours = ((adapter as { sent?: { pane: string; kind: string; value: string }[] }).sent ?? []).filter((x) => x.pane === paneId && x.kind === "prompt");
    const turn = (text: string) =>
      s.agent === "codex" ? { type: "event_msg", timestamp: at(0), payload: { type: "user_message", message: text } }
      : s.agent === "pi" ? { type: "message", timestamp: at(0), message: { role: "user", content: text } }
      : { type: "user", timestamp: at(0), message: { role: "user", content: text } };
    // Claude's file carries its subagents after separator lines; your turns go before them.
    const [first, ...subs] = (s.agent === "codex" ? codexSession() + "\n" + files.codex() : s.agent === "pi" ? files.pi() : claudeSession()).split("\n\u001e\n");
    const main = s.agent === "claude" ? first + "\n" + files.claude() : first;
    return [[main, ...yours.map((x) => JSON.stringify(turn(x.value)))].join("\n"), ...subs].join("\n\u001e\n");
  };
  return { run, handle };
}
