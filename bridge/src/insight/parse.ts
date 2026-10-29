import type { BackgroundTask, Insight, Message, Subagent } from "../protocol.ts";

/** One parsed line of a harness session file. Lines that don't parse (a cut first line) are dropped. */
type Line = Record<string, any>;

export type Reading = Omit<Insight, "session_id" | "harness" | "read_at" | "messages">;

export function lines(text: string): Line[] {
  const out: Line[] = [];
  for (const l of text.split("\n")) {
    if (!l.startsWith("{")) continue;
    try { out.push(JSON.parse(l)); } catch { /* a line cut by the tail read */ }
  }
  return out;
}

const empty = (): Reading => ({ model: null, effort: null, context: null, subagents: [], tasks: [], last_reply: null, limit: null, step: null });
const LIMIT = /(usage|rate) limit|limit (reached|exceeded)|resets? (at|in) /i;
const clip = (s: string, n = 600) => (s.length > n ? s.slice(0, n - 1) + "…" : s);

function text(content: unknown): string {
  if (typeof content === "string") return content;
  if (!Array.isArray(content)) return "";
  return content.map((c) => (c?.type === "text" || c?.type === "output_text" || c?.type === "input_text" ? c.text ?? "" : "")).join("\n").trim();
}

/** `<task-notification>` blocks the harness injects when a background command or agent ends. */
function notifications(s: string): { id: string; toolUse: string | null; status: string; exit: number | null }[] {
  return [...s.matchAll(/<task-notification>([\s\S]*?)<\/task-notification>/g)].flatMap((m) => {
    const id = m[1].match(/<task-id>([^<]+)<\/task-id>/)?.[1]?.trim();
    const status = m[1].match(/<status>([^<]+)<\/status>/)?.[1]?.trim();
    const exit = m[1].match(/<summary>[^<]*\(exit code (\d+)\)/)?.[1];
    return id && status ? [{ id, toolUse: m[1].match(/<tool-use-id>([^<]+)<\/tool-use-id>/)?.[1]?.trim() ?? null, status, exit: exit ? Number(exit) : null }] : [];
  });
}

const ended = (status: string): "done" | "failed" | "stopped" => (/fail|error/i.test(status) ? "failed" : /kill|stop|cancel/i.test(status) ? "stopped" : "done");

/** A background command as read, with the host path of its output file kept for the host to tail. */
export type TaskRead = BackgroundTask & { output_file?: string };

/**
 * Claude Code: `~/.claude/projects/<cwd>/<session>.jsonl`, plus `<session>/subagents/agent-*.jsonl`
 * transcripts in [subs], each led by its `.meta.json` line naming the tool call that started it.
 */
export function claude(ls: Line[], subs: Line[][] = []): Reading {
  const r = empty();
  const agents = new Map<string, Subagent & { handle?: string }>();
  const tasks = new Map<string, TaskRead & { handle?: string }>();
  for (const o of ls) {
    if (typeof o.effort === "string") r.effort = o.effort;
    const notes = o.type === "queue-operation" && o.operation === "enqueue" && typeof o.content === "string" ? notifications(o.content)
      : o.type === "user" && o.message ? notifications(typeof o.message.content === "string" ? o.message.content : text(o.message.content)) : [];
    for (const n of notes) {
      for (const x of [...agents.values(), ...tasks.values()]) {
        if (x.handle !== n.id && x.id !== n.toolUse) continue;
        x.state = "command" in x ? ended(n.status) : ended(n.status) === "failed" ? "failed" : "done";
        x.ended_at = o.timestamp ?? "";
        if ("command" in x) x.exit_code = n.exit;
      }
    }
    const m = o.message;
    if (!m || typeof m !== "object") continue;
    const at: string = o.timestamp ?? "";
    if (o.type === "assistant") {
      if (m.model && m.model !== "<synthetic>") r.model = m.model;
      const u = m.usage;
      if (u) r.context = { used: (u.input_tokens ?? 0) + (u.cache_read_input_tokens ?? 0) + (u.cache_creation_input_tokens ?? 0), window: null };
      const said = text(m.content);
      if (said) {
        r.last_reply = { text: clip(said), at };
        r.limit = LIMIT.test(said) ? { message: clip(said, 200), at } : null;
      }
      for (const c of Array.isArray(m.content) ? m.content : []) {
        if (c?.type !== "tool_use") continue;
        if (c.name === "Agent" || c.name === "Task") {
          agents.set(c.id, { id: c.id, description: c.input?.description ?? c.input?.prompt?.slice(0, 80) ?? "Subagent", type: c.input?.subagent_type ?? null, state: "working", started_at: at, ended_at: null, step: null, model: null, context_used: null, active_at: null });
        } else if (c.name === "Bash" && c.input?.run_in_background) {
          tasks.set(c.id, { id: c.id, command: c.input?.description || c.input?.command || "command", state: "running", started_at: at, ended_at: null, exit_code: null, tail: [] });
        }
      }
    } else if (o.type === "user") {
      for (const c of Array.isArray(m.content) ? m.content : []) {
        if (c?.type !== "tool_result") continue;
        const out = text(c.content) || (typeof c.content === "string" ? c.content : "");
        const handle = out.match(/(?:with ID|agentId|task[_ ]id)[:\s]+([A-Za-z0-9_-]+)/i)?.[1];
        const a = agents.get(c.tool_use_id);
        if (a) {
          if (handle && /background|launched|async/i.test(out)) a.handle = handle;
          else { a.state = c.is_error ? "failed" : "done"; a.ended_at = at; }
        }
        const t = tasks.get(c.tool_use_id);
        if (t) {
          t.output_file = out.match(/written to: (\S+\.output)/)?.[1] ?? t.output_file;
          if (handle) t.handle = handle;
          else { t.state = c.is_error ? "failed" : "done"; t.ended_at = at; }
        }
      }
    }
  }
  for (const sub of subs) {
    const a = agents.get(sub[0]?.toolUseId);
    if (a) Object.assign(a, subagent(sub.slice(1)), { type: a.type ?? sub[0].agentType ?? null });
  }
  r.subagents = [...agents.values()].map(({ handle: _, ...a }) => a);
  r.tasks = [...tasks.values()].map(({ handle: _, ...t }) => t);
  // The session's own latest step reads the same way as a subagent's.
  r.step = subagent(ls).step;
  return r;
}

/** What a subagent's own transcript says it is doing: its last tool call, else its last words. */
export function subagent(ls: Line[]): Pick<Subagent, "step" | "model" | "context_used" | "active_at"> {
  const out: Pick<Subagent, "step" | "model" | "context_used" | "active_at"> = { step: null, model: null, context_used: null, active_at: null };
  for (const o of ls) {
    if (o.timestamp) out.active_at = o.timestamp;
    const m = o.message;
    if (o.type !== "assistant" || !m || typeof m !== "object") continue;
    if (m.model && m.model !== "<synthetic>") out.model = m.model;
    const u = m.usage;
    if (u) out.context_used = (u.input_tokens ?? 0) + (u.cache_read_input_tokens ?? 0) + (u.cache_creation_input_tokens ?? 0);
    const content = Array.isArray(m.content) ? m.content : [];
    const tool = content.findLast((c: any) => c?.type === "tool_use");
    const said = text(m.content);
    if (tool) {
      const i = tool.input ?? {};
      const what = i.description ?? i.command ?? i.file_path ?? i.pattern ?? i.url ?? i.query ?? "";
      out.step = clip(`${tool.name}${what ? `: ${String(what).split("\n")[0]}` : ""}`, 120);
    } else if (said) out.step = clip(said.split("\n").find((l) => l.trim()) ?? said, 120);
  }
  return out;
}

/** Pi: `~/.pi/agent/sessions/<cwd>/<time>_<id>.jsonl`; Herdr reports the path directly. */
export function pi(ls: Line[]): Reading {
  const r = empty();
  for (const o of ls) {
    const at: string = o.timestamp ?? "";
    if (o.type === "model_change") r.model = o.modelId ?? r.model;
    if (o.type === "thinking_level_change") r.effort = o.thinkingLevel ?? r.effort;
    const m = o.message;
    if (o.type !== "message" || m?.role !== "assistant") continue;
    if (m.model) r.model = m.model;
    const u = m.usage;
    if (u && (u.input || u.cacheRead)) r.context = { used: (u.input ?? 0) + (u.cacheRead ?? 0) + (u.cacheWrite ?? 0), window: null };
    if (m.stopReason === "error" && m.errorMessage) r.limit = LIMIT.test(m.errorMessage) ? { message: clip(m.errorMessage, 200), at } : r.limit;
    const said = text(m.content);
    if (said) { r.last_reply = { text: clip(said), at }; r.limit = LIMIT.test(said) ? { message: clip(said, 200), at } : null; }
  }
  return r;
}

/** Codex: `~/.codex/sessions/YYYY/MM/DD/rollout-…-<id>.jsonl`. */
export function codex(ls: Line[]): Reading {
  const r = empty();
  let window: number | null = null;
  for (const o of ls) {
    const at: string = o.timestamp ?? "";
    const p = o.payload ?? {};
    if (o.type === "turn_context") {
      r.model = p.model ?? p.collaboration_mode?.settings?.model ?? r.model;
      r.effort = p.effort ?? p.collaboration_mode?.settings?.reasoning_effort ?? r.effort;
    }
    if (o.type !== "event_msg") continue;
    if (p.type === "thread_settings_applied") r.model = p.thread_settings?.model ?? r.model;
    if (p.type === "task_started" && p.model_context_window) window = p.model_context_window;
    if (p.type === "token_count" && p.info) {
      window = p.info.model_context_window ?? window;
      const u = p.info.last_token_usage ?? p.info.total_token_usage;
      if (u) r.context = { used: u.input_tokens ?? 0, window };
    }
    if (p.type === "task_complete" && p.last_agent_message) r.last_reply = { text: clip(p.last_agent_message), at };
    if (p.type === "error" && LIMIT.test(p.message ?? "")) r.limit = { message: clip(p.message, 200), at };
  }
  if (r.context) r.context.window = window;
  return r;
}

/** OpenCode: rows from its SQLite store, one `{ type: "oc", at, msg, parts }` line per message (see host.ts). */
export function opencode(ls: Line[]): Reading {
  const r = empty();
  const agents = new Map<string, Subagent>();
  for (const o of ls) {
    if (o.type !== "oc" || !o.msg) continue;
    const m = o.msg, at = new Date(o.at).toISOString();
    if (m.role !== "assistant") continue;
    r.model = m.modelID ?? r.model;
    r.effort = m.variant ?? r.effort;
    const t = m.tokens;
    const used = (t?.input ?? 0) + (t?.cache?.read ?? 0) + (t?.cache?.write ?? 0);
    if (used) r.context = { used, window: null };
    const said = (o.parts ?? []).filter((p: any) => p?.type === "text").map((p: any) => p.text ?? "").join("\n").trim();
    if (said) { r.last_reply = { text: clip(said), at }; r.limit = LIMIT.test(said) ? { message: clip(said, 200), at } : null; }
    const err = m.error ? String(m.error.data?.message ?? m.error.name ?? "") : "";
    if (err && LIMIT.test(err)) r.limit = { message: clip(err, 200), at };
    for (const p of o.parts ?? []) {
      if (p?.type !== "tool" || !p.id) continue;
      const state = p.status === "completed" ? "done" : p.status === "error" ? "failed" : "working";
      agents.set(p.id, { id: p.id, description: p.description ?? "Subagent", type: p.agent ?? null, state, started_at: p.start ? new Date(p.start).toISOString() : at,
        ended_at: state === "working" ? null : p.end ? new Date(p.end).toISOString() : at });
    }
  }
  r.subagents = [...agents.values()];
  return r;
}

export const readers: Record<string, (ls: Line[], subs?: Line[][]) => Reading> = { claude, pi, codex, opencode };

// ── The conversation: what you said and what the agent said back, without tool noise ────────

/** How the bridge words a photo it saved for the agent; [said] reads it back as a photo. */
export const PHOTO = "Photo from my R1 (read this image file):";
const MESSAGES = 30;

function said(role: Message["role"], raw: string, at: string): Message[] {
  const t = raw.trim();
  // Harness-injected turns (`<command-name>`, `<task-notification>`, `<system-reminder>` …) are not you.
  if (!t || (role === "you" && t.startsWith("<"))) return [];
  const photo = t.startsWith(PHOTO) ? t.slice(PHOTO.length).trim().split(/\s/, 1)[0] : null;
  return [{ role, text: clip(photo ? t.slice(PHOTO.length).trim().slice(photo.length).trim() : t), at, photo }];
}

export function claudeMessages(ls: Line[]): Message[] {
  return ls.flatMap((o) => {
    const m = o.message;
    if (!m || o.isMeta || o.isSidechain) return [];
    if (o.type === "user" && (typeof m.content === "string" || (Array.isArray(m.content) && m.content.every((c: any) => c?.type === "text"))))
      return said("you", text(m.content), o.timestamp ?? "");
    if (o.type === "assistant") return said("agent", text(m.content), o.timestamp ?? "");
    return [];
  }).slice(-MESSAGES);
}

export function piMessages(ls: Line[]): Message[] {
  return ls.flatMap((o) => {
    const role = o.type === "message" ? o.message?.role : null;
    return role === "user" || role === "assistant" ? said(role === "user" ? "you" : "agent", text(o.message.content), o.timestamp ?? "") : [];
  }).slice(-MESSAGES);
}

export function codexMessages(ls: Line[]): Message[] {
  return ls.flatMap((o) => {
    const p = o.type === "event_msg" ? o.payload : null;
    return p?.type === "user_message" || p?.type === "agent_message" ? said(p.type === "user_message" ? "you" : "agent", String(p.message ?? ""), o.timestamp ?? "") : [];
  }).slice(-MESSAGES);
}

export function opencodeMessages(ls: Line[]): Message[] {
  return ls.flatMap((o) => {
    const role = o.type === "oc" ? o.msg?.role : null;
    if (role !== "user" && role !== "assistant") return [];
    const t = (o.parts ?? []).filter((p: any) => p?.type === "text" && !p.synthetic).map((p: any) => p.text ?? "").join("\n");
    return said(role === "user" ? "you" : "agent", t, new Date(o.at).toISOString());
  }).slice(-MESSAGES);
}

export const messageReaders: Record<string, (ls: Line[]) => Message[]> = { claude: claudeMessages, pi: piMessages, codex: codexMessages, opencode: opencodeMessages };
