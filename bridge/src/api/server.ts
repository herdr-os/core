import http from "node:http";
import https from "node:https";
import { readFileSync } from "node:fs";
import { canAccessMachine, type Device, type Devices } from "../auth/devices.ts";
import type { EventLog } from "../events/log.ts";
import type { Herd } from "../index/herd.ts";
import { paneOf } from "../index/herd.ts";
import { HerdrError } from "../herdr-adapter/socket.ts";
import { parseDecision } from "../decisions/parse.ts";
import { compactLines, sanitizeTerminal, redactSecrets } from "../terminal/sanitize.ts";
import { fileDiff, GitError, listFiles, revision } from "../diff/git.ts";
import { resolveIntent } from "../intents/resolve.ts";
import { IdempotencyConflict, type Receipts } from "../receipts/receipts.ts";
import { PROTOCOL_VERSION, type ActionReceipt, type Scope, type Session } from "../protocol.ts";
import type { Insights } from "../insight/host.ts";
import { PHOTO } from "../insight/parse.ts";
import { createHash } from "node:crypto";
import type { Store } from "../store.ts";

export interface Deps {
  db: Store;
  devices: Devices;
  events: EventLog;
  herd: Herd;
  insights: Insights;
  receipts: Receipts;
}

const MAX_BODY = 64 * 1024;
const MAX_EXPLAIN_CHARS = 4000;
const MAX_MESSAGE_CHARS = 8000;
const MAX_PHOTO = 5 * 1024 * 1024;

class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details?: Record<string, unknown>;
  constructor(status: number, code: string, message: string, details?: Record<string, unknown>) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

type Ctx = { req: http.IncomingMessage; res: http.ServerResponse; url: URL; device: Device | null; params: string[]; body: any };
type Handler = (c: Ctx, d: Deps) => Promise<unknown> | unknown;
interface Route {
  method: string;
  pattern: RegExp;
  scope: Scope | null;
  mutation?: string;
  handler: Handler;
}

const routes: Route[] = [];
const route = (method: string, path: string, scope: Scope | null, handler: Handler, mutation?: string) =>
  routes.push({ method, pattern: new RegExp("^" + path.replace(/\{[a-z_]+\}/g, "([^/]+)") + "$"), scope, handler, mutation });

// ── Unauthenticated ────────────────────────────────────────────────────────────────────────
route("GET", "/v1/health", null, () => ({ ok: true, protocol: PROTOCOL_VERSION }));
route("POST", "/v1/enrollments/claim", null, (c, d) => {
  const claim = d.devices.claim(String(c.body?.code ?? ""), String(c.body?.name ?? "Rabbit R1"));
  if (!claim) throw new ApiError(400, "invalid_code", "Code is invalid, expired or already used.");
  audit(d, claim.device.device_id, "enroll", null, "accepted");
  return { device_id: claim.device.device_id, token: claim.token, scopes: claim.device.scopes, protocol: PROTOCOL_VERSION };
});

// ── Read API ───────────────────────────────────────────────────────────────────────────────
route("GET", "/v1/me", "read", (c) => ({ device: c.device, protocol: PROTOCOL_VERSION }));
route("GET", "/v1/machines", "read", (c, d) => ({ machines: [...d.herd.machines.values()].filter((m) => canAccessMachine(c.device!, m.machine_id)) }));
route("GET", "/v1/agents", "read", (c, d) => ({ agents: visible(c, d, c.url.searchParams.get("machine_id") ?? undefined), cursor: d.events.head() }));
route("GET", "/v1/snapshot", "read", (c, d) => {
  const agents = visible(c, d);
  return {
    cursor: d.events.head(),
    machines: [...d.herd.machines.values()].filter((m) => canAccessMachine(c.device!, m.machine_id)),
    agents,
    approvals: agents.flatMap((s) => (s.pending_approval_id ? [publicApproval(d.herd.approvals.get(s.pending_approval_id)!)] : [])),
    synced_at: new Date().toISOString(),
  };
});
route("GET", "/v1/sessions/{id}", "read", (c, d) => {
  const s = session(c, d);
  d.herd.markSeen(s.session_id);
  const approval = s.pending_approval_id ? d.herd.approvals.get(s.pending_approval_id) : null;
  return { session: d.herd.get(s.session_id), approval: approval ? publicApproval(approval) : null };
});
route("GET", "/v1/sessions/{id}/insight", "read", async (c, d) => ({ insight: await d.insights.get(session(c, d)) }));
route("GET", "/v1/sessions/{id}/activity", "read", (c, d) => ({ events: d.events.forSession(session(c, d).session_id, c.url.searchParams.get("cursor"), 50) }));
route("GET", "/v1/activity", "read", (c, d) => {
  const allowed = new Set(visible(c, d).map((s) => s.session_id));
  const types = ["agent.blocked", "agent.completed", "agent.failed", "agent.started", "approval.resolved", "action.receipt", "machine.connected", "machine.disconnected"] as const;
  return { events: d.events.recent(Number(c.url.searchParams.get("limit") ?? 30), [...types]).filter((e) => !e.session_id || allowed.has(e.session_id)) };
});
route("GET", "/v1/sessions/{id}/terminal/snapshot", "read", async (c, d) => {
  const s = session(c, d);
  const max = clamp(Number(c.url.searchParams.get("max_lines") ?? 200), 1, 1000);
  const mode = c.url.searchParams.get("mode") === "raw" ? "raw" : "compact";
  const { adapter } = d.herd.adapterFor(s.session_id);
  const read = await adapter.read(paneOf(s.session_id), max, { ansi: true });
  const clean = sanitizeTerminal(read.text);
  const lines = mode === "compact" ? compactLines(clean.lines) : clean.lines;
  const machine = d.herd.machines.get(s.machine_id)!;
  return {
    session_id: s.session_id,
    generation: s.generation,
    mode,
    cwd: s.cwd,
    revision: read.revision,
    truncated: read.truncated,
    redactions: clean.redactions,
    neutralized: clean.neutralized,
    lines,
    stream: machine.status === "connected" ? "live" : "offline",
    captured_at: new Date().toISOString(),
  };
});
route("GET", "/v1/sessions/{id}/changes", "read", async (c, d) => {
  const s = session(c, d);
  const rev = await revision(localCwd(s, d));
  const expected = c.url.searchParams.get("revision");
  return { ...rev, repo_root: undefined, repo_name: rev.repo_root.split("/").pop(), stale_request: expected ? expected !== rev.fingerprint : false };
});
route("GET", "/v1/sessions/{id}/changes/{file}", "read", async (c, d) => fileDiff(localCwd(session(c, d), d), c.url.searchParams.get("revision"), c.params[1]));
route("GET", "/v1/sessions/{id}/files", "read", async (c, d) => listFiles(localCwd(session(c, d), d), c.url.searchParams.get("path") ?? ""));
route("GET", "/v1/approvals/{id}", "read", (c, d) => {
  const a = d.herd.approvals.get(c.params[0]);
  if (!a) throw new ApiError(404, "approval_not_found", "No such approval.");
  session({ ...c, params: [a.session_id] }, d);
  return { approval: publicApproval(a) };
});
route("GET", "/v1/actions/{id}", "read", (c, d) => {
  const r = d.receipts.get(c.params[0]);
  if (!r || r.device_id !== c.device!.device_id) throw new ApiError(404, "action_not_found", "No such action for this device.");
  return { receipt: r };
});
route("GET", "/v1/actions", "read", (c, d) => {
  const r = d.receipts.byKey(c.device!.device_id, c.url.searchParams.get("idempotency_key") ?? "");
  if (!r) throw new ApiError(404, "action_not_found", "No action with that Idempotency-Key.");
  return { receipt: r };
});
route("POST", "/v1/intents/resolve", "read", (c, d) => resolveIntent(String(c.body?.text ?? ""), visible(c, d), [...d.herd.machines.values()], c.body?.focus_session_id ?? null));

// ── Actions ────────────────────────────────────────────────────────────────────────────────
route("POST", "/v1/sessions/{id}/messages", "act", (c, d) => mutate(c, d, "message", session(c, d), async (s, { adapter, paneId }) => {
  const text = String(c.body?.text ?? "").trim();
  if (!text || text.length > MAX_MESSAGE_CHARS) throw new ApiError(400, "bad_text", `Message must be 1–${MAX_MESSAGE_CHARS} characters.`);
  await adapter.prompt(paneId, text);
  return { delivered_to: s.session_id };
}), "message");
route("POST", "/v1/sessions/{id}/attachments", "act", (c, d) => {
  const jpeg: Buffer = Buffer.isBuffer(c.body) ? c.body : Buffer.alloc(0);
  if (jpeg.length < 4 || jpeg[0] !== 0xff || jpeg[1] !== 0xd8 || jpeg[2] !== 0xff) throw new ApiError(415, "not_jpeg", "Send the photo as an image/jpeg body.");
  const note = (c.url.searchParams.get("note") ?? "").trim().slice(0, MAX_MESSAGE_CHARS);
  const gen = c.url.searchParams.get("expected_generation");
  // The receipt hashes this summary, not the bytes: the same photo retried under one key is one send.
  const body = { sha256: createHash("sha256").update(jpeg).digest("hex"), bytes: jpeg.length, note, ...(gen === null ? {} : { expected_generation: Number(gen) }) };
  return mutate({ ...c, body }, d, "attachment", session(c, d), async (s, { adapter, paneId }) => {
    const run = d.insights.runFor(s.machine_id);
    if (!run) throw new ApiError(501, "attachments_unavailable", "The bridge can't write files on that machine.");
    // Inside the project so the agent may read it without leaving its workspace; the folder ignores itself in Git.
    const dir = s.cwd?.startsWith("/") ? quote(`${s.cwd}/.herdr-attachments`) : `"\${TMPDIR:-/tmp}/herdr-attachments"`;
    const name = `r1-${new Date().toISOString().replace(/[:.]/g, "-")}.jpg`;
    const b64 = jpeg.toString("base64").replace(/.{76}/g, "$&\n");
    // ponytail: the photo rides the script's stdin as base64 under the 8 s run timeout; a slow SSH link needs a longer one.
    const path = (await run(`d=${dir}; mkdir -p "$d" && printf '*\\n' > "$d/.gitignore" && base64 -d > "$d/${name}" <<'HERDR_JPEG'\n${b64}\nHERDR_JPEG\n[ -s "$d/${name}" ] && printf '%s' "$d/${name}"`).catch(() => "")).trim();
    if (!path) throw new ApiError(502, "write_failed", "The photo could not be saved on that machine.");
    await adapter.prompt(paneId, `${PHOTO} ${path}${note ? `\n\n${note}` : ""}`);
    return { path, bytes: jpeg.length };
  });
}, "attachment");
route("POST", "/v1/tasks/delegate", "act", (c, d) => {
  const s = session({ ...c, params: [String(c.body?.session_id ?? "")] }, d);
  return mutate(c, d, "delegate", s, async (_s, { adapter, paneId }) => {
    const text = String(c.body?.instruction ?? "").trim();
    if (!text || text.length > MAX_MESSAGE_CHARS) throw new ApiError(400, "bad_text", "Instruction is empty or too long.");
    await adapter.prompt(paneId, text);
    return { task_id: "task_" + c.req.headers["idempotency-key"], session_id: s.session_id };
  });
}, "delegate");
route("POST", "/v1/sessions/{id}/actions/explain", "act", (c, d) => mutate(c, d, "explain", session(c, d), async (s, { adapter, paneId }) => {
  if (c.body?.consent !== true) throw new ApiError(400, "consent_required", "Sending terminal text to the agent needs explicit consent.");
  const raw = String(c.body?.text ?? "").slice(0, MAX_EXPLAIN_CHARS);
  const { text } = redactSecrets(raw);
  if (!text.trim()) throw new ApiError(400, "bad_text", "Select some output first.");
  await adapter.prompt(paneId, `Explain this output from your terminal in ${s.cwd ?? s.project} and what you would do next. Do not change anything yet.\n\n\`\`\`\n${text}\n\`\`\``);
  return { sent_chars: text.length };
}), "explain");
route("POST", "/v1/sessions/{id}/actions/stop", "act", (c, d) => mutate(c, d, "stop", session(c, d), async (_s, { adapter, paneId }) => {
  await adapter.sendKeys(paneId, ["esc"]);
  return { keys: ["esc"] };
}), "stop");
// The interactive terminal's keys. Only these names reach Herdr's send-keys; text goes through messages.
const TERMINAL_KEYS = new Set(["esc", "tab", "enter", "up", "down", "left", "right", "backspace", "ctrl+c"]);
route("POST", "/v1/sessions/{id}/actions/keys", "act", (c, d) => mutate(c, d, "keys", session(c, d), async (_s, { adapter, paneId }) => {
  const keys = c.body?.keys;
  if (!Array.isArray(keys) || keys.length < 1 || keys.length > 8 || !keys.every((k) => TERMINAL_KEYS.has(k)))
    throw new ApiError(400, "bad_keys", `keys must be 1–8 of: ${[...TERMINAL_KEYS].join(", ")}.`);
  await adapter.sendKeys(paneId, keys);
  return { keys };
}), "keys");
route("POST", "/v1/approvals/{id}/decision", "act", (c, d) => decide(c, d, c.params[0]), "decision");
route("POST", "/v1/sessions/{id}/actions/respond", "act", (c, d) => {
  const s = session(c, d);
  if (!s.pending_approval_id) throw new ApiError(409, "not_blocked", "This agent is not waiting for a decision.");
  return decide(c, d, String(c.body?.approval_id ?? s.pending_approval_id));
}, "respond");
route("POST", "/v1/sessions/{id}/actions/review", "review", (c, d) => mutate(c, d, "review", session(c, d), async (s, { adapter, paneId }) => {
  const expected = String(c.body?.revision ?? "");
  const decision = c.body?.decision;
  if (decision !== "approve" && decision !== "request_changes") throw new ApiError(400, "bad_decision", "decision must be approve or request_changes");
  const rev = await revision(localCwd(s, d));
  if (!expected || expected !== rev.fingerprint)
    throw new ApiError(409, "revision_changed", "The code changed since you reviewed it. Review the new revision.", { current_revision: rev.fingerprint, file_count: rev.files.length });
  const scope = { repo_id: rev.repo_id, base_commit: rev.base_commit, revision: rev.fingerprint, file_count: rev.files.length };
  if (decision === "request_changes") {
    const note = String(c.body?.note ?? "").trim().slice(0, MAX_MESSAGE_CHARS);
    if (!note) throw new ApiError(400, "bad_text", "Say what should change.");
    const where = c.body?.file_path ? ` in ${String(c.body.file_path)}${c.body?.hunk_header ? ` (${String(c.body.hunk_header)})` : ""}` : "";
    await adapter.prompt(paneId, `Review feedback on your uncommitted changes${where}: ${note}`);
  }
  // `approve` is a Herdr review acknowledgment bound to this exact revision. It does not merge,
  // push or touch GitHub.
  return { decision, ...scope, meaning: decision === "approve" ? "herdr_review_acknowledgment" : "feedback_sent" };
}, (body) => ({ revision: body?.revision })), "review");

// ── Admin (bridge host only via CLI; exposed for completeness behind admin scope) ───────────
route("POST", "/v1/devices/{id}/revoke", "admin", (c, d) => ({ revoked: d.devices.revoke(c.params[0]) }), "revoke");

// ── Helpers ────────────────────────────────────────────────────────────────────────────────

function visible(c: Ctx, d: Deps, machineId?: string): Session[] {
  return d.herd.list(machineId).filter((s) => canAccessMachine(c.device!, s.machine_id));
}

function session(c: Ctx, d: Deps): Session {
  const id = decodeURIComponent(c.params[0] ?? "");
  const s = d.herd.get(id);
  if (!s || !canAccessMachine(c.device!, s.machine_id)) throw new ApiError(404, "session_not_found", "No such session.");
  return s;
}

function localCwd(s: Session, d: Deps): string {
  if (d.herd.machines.get(s.machine_id)?.kind !== "local") throw new ApiError(501, "diff_unavailable_remote", "Diffs for SSH machines need a bridge adapter on that host.");
  if (!s.cwd) throw new ApiError(409, "no_cwd", "Herdr did not report a working directory for this agent.");
  return s.cwd;
}

function publicApproval(a: import("../protocol.ts").Approval) {
  return { ...a, evidence: redactSecrets(a.evidence).text };
}

async function decide(c: Ctx, d: Deps, approvalId: string) {
  const a = d.herd.approvals.get(approvalId);
  if (!a) throw new ApiError(404, "approval_not_found", "No such approval.");
  const s = session({ ...c, params: [a.session_id] }, d);
  return mutate(c, d, "decision", s, async (_s, { adapter, paneId }) => {
    if (a.status !== "open") throw new ApiError(409, "approval_closed", `This decision is already ${a.status}.`);
    if (c.body?.evidence_fingerprint !== a.evidence_fingerprint) throw new ApiError(409, "approval_changed", "The question changed. Review it again.");
    const option = a.options.find((o) => o.index === Number(c.body?.option_index));
    if (!option) throw new ApiError(400, "bad_option", "Choose one of the offered options.");
    // Re-read the live screen right before sending: the key goes out only if the exact same
    // question is still on screen in the same agent generation.
    const fresh = parseDecision((await adapter.read(paneId, 60, { source: "visible" })).text);
    const live = (await adapter.listAgents()).find((x) => x.pane_id === paneId);
    if (live?.agent_status !== "blocked" || fresh.fingerprint !== a.evidence_fingerprint || s.generation !== a.generation) {
      d.herd.closeApproval(s, "expired");
      throw new ApiError(409, "approval_changed", "The agent moved on before your answer was sent. Nothing was sent.");
    }
    await adapter.sendKeys(paneId, [option.key]);
    d.herd.closeApproval(s, "resolved");
    return { approval_id: a.approval_id, chosen: option.label };
  }, () => ({ approval_id: a.approval_id, evidence_fingerprint: a.evidence_fingerprint }));
}

/**
 * Runs one mutation under its receipt. Requires Idempotency-Key; a retried key returns the
 * original receipt and never dispatches twice. Herdr transport failures settle as `unknown`
 * (the write may have landed), never as a silent retry.
 */
async function mutate(
  c: Ctx,
  d: Deps,
  kind: string,
  s: Session,
  run: (s: Session, t: ReturnType<Herd["adapterFor"]>) => Promise<Record<string, unknown>>,
  extraScope: (body: any) => Record<string, unknown> = () => ({}),
): Promise<{ receipt: ActionReceipt }> {
  const key = String(c.req.headers["idempotency-key"] ?? "");
  if (!/^[A-Za-z0-9_.:-]{8,128}$/.test(key)) throw new ApiError(400, "idempotency_key_required", "Mutations need an Idempotency-Key header (8–128 chars).");
  const expectedGen = c.body?.expected_generation;
  const scope = { session_id: s.session_id, machine_id: s.machine_id, agent: s.agent, generation: s.generation, ...extraScope(c.body) };
  let begun;
  try {
    begun = d.receipts.begin(c.device!.device_id, key, kind, scope, c.body);
  } catch (e) {
    if (e instanceof IdempotencyConflict) throw new ApiError(409, e.code, e.message);
    throw e;
  }
  if (!begun.fresh) return { receipt: begun.receipt };
  const { receipt } = begun;

  let settled: ActionReceipt;
  try {
    if (expectedGen !== undefined && Number(expectedGen) !== s.generation)
      throw new ApiError(409, "generation_changed", "The agent restarted since you looked. Nothing was sent.", { generation: s.generation });
    if (s.stale) throw new ApiError(409, "machine_offline", "That machine is offline. Nothing was sent.");
    const result = await run(s, d.herd.adapterFor(s.session_id));
    settled = d.receipts.settle(receipt.action_id, "accepted", null, result);
  } catch (e) {
    if (e instanceof ApiError) settled = d.receipts.settle(receipt.action_id, "rejected", `${e.code}: ${e.message}`, e.details ?? null);
    else if (e instanceof GitError) settled = d.receipts.settle(receipt.action_id, "rejected", `${e.code}: ${e.message}`);
    else if (e instanceof HerdrError && ["agent_blocked", "agent_not_found", "pane_not_found", "invalid_request", "invalid_key", "agent_busy"].includes(e.code))
      settled = d.receipts.settle(receipt.action_id, "rejected", `${e.code}: ${e.message}`);
    else settled = d.receipts.settle(receipt.action_id, "unknown", `delivery uncertain: ${(e as Error).message}`);
  }
  audit(d, c.device!.device_id, kind, s.session_id, settled.status);
  d.events.append("action.receipt", { machine_id: s.machine_id, session_id: s.session_id, generation: s.generation, payload: { action_id: settled.action_id, kind, status: settled.status, detail: settled.detail } });
  return { receipt: settled };
}

function audit(d: Deps, device: string | null, action: string, target: string | null, outcome: string) {
  d.db.prepare("INSERT INTO audit (at, device_id, action, target, outcome) VALUES (?, ?, ?, ?, ?)").run(new Date().toISOString(), device, action, target, outcome);
}

const quote = (v: string) => `'${v.replace(/'/g, `'\\''`)}'`;

const clamp = (n: number, lo: number, hi: number) => (Number.isFinite(n) ? Math.min(hi, Math.max(lo, Math.floor(n))) : hi);

// ── Server ─────────────────────────────────────────────────────────────────────────────────

export function createHandler(d: Deps) {
  return async (req: http.IncomingMessage, res: http.ServerResponse) => {
    const url = new URL(req.url ?? "/", "http://bridge");
    res.setHeader("Cache-Control", "no-store");
    res.setHeader("X-Content-Type-Options", "nosniff");
    try {
      if (req.method === "GET" && url.pathname === "/v1/events") return await events(req, res, url, d);
      const match = routes.find((r) => r.method === req.method && r.pattern.test(url.pathname));
      if (!match) throw new ApiError(404, "not_found", `${req.method} ${url.pathname}`);
      const device = authorize(req, d, match.scope);
      const body = req.method !== "POST" ? null : req.headers["content-type"]?.startsWith("image/jpeg") ? await readBytes(req, MAX_PHOTO) : await readJson(req);
      const params = match.pattern.exec(url.pathname)!.slice(1).map(decodeURIComponent);
      const out = await match.handler({ req, res, url, device, params, body }, d);
      send(res, 200, out);
    } catch (e) {
      if (e instanceof ApiError) return send(res, e.status, { error: { code: e.code, message: e.message, details: e.details } });
      if (e instanceof GitError) return send(res, e.code === "revision_changed" ? 409 : e.code === "forbidden_path" ? 403 : 422, { error: { code: e.code, message: e.message } });
      if (e instanceof HerdrError) return send(res, 502, { error: { code: `herdr_${e.code}`, message: e.message } });
      const code = (e as { code?: string }).code;
      if (code === "session_not_found") return send(res, 404, { error: { code, message: (e as Error).message } });
      if (code === "bad_cursor") return send(res, 400, { error: { code, message: (e as Error).message } });
      console.error(e);
      send(res, 500, { error: { code: "internal", message: "Internal error" } });
    }
  };
}

function authorize(req: http.IncomingMessage, d: Deps, scope: Scope | null): Device | null {
  if (!scope) return null;
  const auth = req.headers.authorization;
  const device = d.devices.authenticate(auth?.startsWith("Bearer ") ? auth.slice(7) : undefined);
  if (!device) throw new ApiError(401, "unauthorized", "Missing, invalid or revoked device token.");
  if (!device.scopes.includes(scope)) throw new ApiError(403, "forbidden_scope", `This device lacks the ${scope} scope.`);
  return device;
}

async function events(req: http.IncomingMessage, res: http.ServerResponse, url: URL, d: Deps) {
  const auth = req.headers.authorization;
  const token = auth?.startsWith("Bearer ") ? auth.slice(7) : undefined;
  const device = authorize(req, d, "read")!;
  const after = url.searchParams.get("after") ?? (req.headers["last-event-id"] as string | undefined) ?? null;
  const allowed = (machineId: string | null) => !machineId || canAccessMachine(device, machineId);

  if (!String(req.headers.accept ?? "").includes("text/event-stream")) {
    const page = d.events.after(after);
    return send(res, 200, { ...page, events: page.events.filter((e) => allowed(e.machine_id)) });
  }

  res.writeHead(200, { "Content-Type": "text/event-stream", "Cache-Control": "no-store", Connection: "keep-alive" });
  const write = (e: { cursor: string; type: string; machine_id: string | null }) => {
    if (allowed(e.machine_id)) res.write(`id: ${e.cursor}\nevent: ${e.type}\ndata: ${JSON.stringify(e)}\n\n`);
  };
  const backlog = d.events.after(after);
  if (backlog.reset_required) res.write(`event: reset_required\ndata: ${JSON.stringify({ cursor: backlog.cursor })}\n\n`);
  let last = after;
  for (const e of backlog.events) {
    write(e);
    last = e.cursor;
  }
  const unsubscribe = d.events.subscribe((e) => {
    if (last && Number(e.cursor.slice(1)) <= Number(last.slice(1))) return;
    last = e.cursor;
    write(e);
  });
  const beat = setInterval(() => {
    // Revocation takes effect on open streams too.
    if (!d.devices.authenticate(token)) {
      res.write(`event: revoked\ndata: {}\n\n`);
      res.end();
      return;
    }
    res.write(`: keepalive ${new Date().toISOString()}\n\n`);
  }, 15000);
  req.on("close", () => {
    clearInterval(beat);
    unsubscribe();
  });
}

async function readJson(req: http.IncomingMessage): Promise<any> {
  const raw = await readBytes(req, MAX_BODY);
  if (!raw.length) return {};
  try {
    return JSON.parse(raw.toString("utf8"));
  } catch {
    throw new ApiError(400, "bad_json", "Body must be JSON.");
  }
}

function readBytes(req: http.IncomingMessage, max: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks: Buffer[] = [];
    req.on("data", (c: Buffer) => {
      size += c.length;
      if (size > max) {
        reject(new ApiError(413, "body_too_large", "Request body too large."));
        // Drained rather than destroyed, so the client reads the 413 instead of a reset.
        req.removeAllListeners("data");
        req.resume();
      } else chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

function send(res: http.ServerResponse, status: number, body: unknown) {
  if (res.headersSent) return res.end();
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(body));
}

export function listen(d: Deps, opts: { host: string; port: number; tlsCert?: string; tlsKey?: string }) {
  const handler = createHandler(d);
  const server = opts.tlsCert && opts.tlsKey ? https.createServer({ cert: readFileSync(opts.tlsCert), key: readFileSync(opts.tlsKey) }, handler) : http.createServer(handler);
  return new Promise<http.Server>((resolve) => server.listen(opts.port, opts.host, () => resolve(server)));
}
