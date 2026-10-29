import path from "node:path";
import { randomBytes } from "node:crypto";
import type { HerdrAdapter, HerdrAgent } from "../herdr-adapter/adapter.ts";
import type { EventLog } from "../events/log.ts";
import { parseDecision } from "../decisions/parse.ts";
import { STATE_PRIORITY, type AgentState, type Approval, type Machine, type Session } from "../protocol.ts";

// Aggregates every Herdr host into one machine-qualified index and turns snapshot diffs into
// events. Transitions are emitted only when the derived state actually changes, so duplicate
// or replayed snapshots never produce duplicate notifications.

const PROGRESS_THROTTLE_MS = 15_000;

interface Tracked {
  session: Session;
  revision: number;
  agentSession: string | null;
  lastProgressAt: number;
  seenCompletion: boolean;
}

export class Herd {
  readonly adapters = new Map<string, HerdrAdapter>();
  readonly machines = new Map<string, Machine>();
  readonly sessions = new Map<string, Tracked>();
  readonly approvals = new Map<string, Approval>();
  readonly events: EventLog;
  private timers: NodeJS.Timeout[] = [];

  constructor(adapters: HerdrAdapter[], events: EventLog) {
    this.events = events;
    for (const a of adapters) {
      this.adapters.set(a.machineId, a);
      this.machines.set(a.machineId, { machine_id: a.machineId, label: a.label, kind: a.kind, status: "disconnected", last_seen_at: null, herdr_version: null });
    }
  }

  start(intervalMs = 1500) {
    for (const id of this.adapters.keys()) {
      const tick = async () => {
        await this.poll(id).catch(() => {});
        this.timers.push(setTimeout(tick, intervalMs));
      };
      void tick();
    }
  }

  stop() {
    for (const t of this.timers) clearTimeout(t);
  }

  adapterFor(sessionId: string): { adapter: HerdrAdapter; paneId: string; tracked: Tracked } {
    const tracked = this.sessions.get(sessionId);
    if (!tracked) throw Object.assign(new Error(`unknown session ${sessionId}`), { code: "session_not_found" });
    const adapter = this.adapters.get(tracked.session.machine_id)!;
    return { adapter, paneId: paneOf(sessionId), tracked };
  }

  list(machineId?: string): Session[] {
    return [...this.sessions.values()]
      .map((t) => t.session)
      .filter((s) => !machineId || s.machine_id === machineId)
      .sort((a, b) => b.priority - a.priority || b.state_since.localeCompare(a.state_since) || a.session_id.localeCompare(b.session_id));
  }

  get(id: string): Session | null {
    return this.sessions.get(id)?.session ?? null;
  }

  /** The harness's own id for the session, or the path of its session file. */
  agentSession(id: string): string | null {
    return this.sessions.get(id)?.agentSession ?? null;
  }

  /** A device opened the session: a finished run is no longer "unseen". */
  markSeen(id: string) {
    const t = this.sessions.get(id);
    if (!t || t.session.state !== "completed_unseen") return;
    t.seenCompletion = true;
    this.transition(t, "idle", t.session.herdr_status);
  }

  async poll(machineId: string) {
    const adapter = this.adapters.get(machineId)!;
    const machine = this.machines.get(machineId)!;
    let agents: HerdrAgent[];
    let version: string | null = machine.herdr_version;
    try {
      if (machine.status === "disconnected") version = (await adapter.ping()).version;
      agents = await adapter.listAgents();
    } catch {
      if (machine.status === "connected") {
        machine.status = "disconnected";
        this.events.append("machine.disconnected", { machine_id: machineId, payload: { label: machine.label } });
        for (const t of this.sessions.values()) if (t.session.machine_id === machineId) this.markStale(t);
      }
      return;
    }
    machine.last_seen_at = new Date().toISOString();
    machine.herdr_version = version;
    if (machine.status === "disconnected") {
      machine.status = "connected";
      this.events.append("machine.connected", { machine_id: machineId, payload: { label: machine.label, herdr_version: version } });
    }

    const live = new Set<string>();
    for (const a of agents) {
      const id = `${machineId}/${a.pane_id}`;
      live.add(id);
      await this.observe(id, machine, adapter, a);
    }
    for (const [id, t] of this.sessions) {
      if (t.session.machine_id !== machineId || live.has(id)) continue;
      this.sessions.delete(id);
      this.closeApproval(t.session, "expired");
      this.events.append("agent.removed", { machine_id: machineId, session_id: id, generation: t.session.generation, payload: { agent: t.session.agent } });
    }
  }

  private async observe(id: string, machine: Machine, adapter: HerdrAdapter, a: HerdrAgent) {
    const agentSession = a.agent_session?.value ?? null;
    let t = this.sessions.get(id);
    const now = new Date().toISOString();
    const cwd = a.foreground_cwd ?? a.cwd ?? null;
    const title = (a.terminal_title_stripped ?? a.title ?? "").replace(/^[\p{S}\p{P}\s]+/u, "").trim();

    if (!t) {
      t = {
        session: {
          session_id: id,
          machine_id: machine.machine_id,
          machine_label: machine.label,
          agent: a.agent ?? "agent",
          name: a.name ?? null,
          title,
          project: cwd ? path.basename(cwd) : "",
          cwd,
          state: "idle",
          herdr_status: a.agent_status,
          priority: STATE_PRIORITY.idle,
          generation: 1,
          state_since: now,
          stale: false,
          pending_approval_id: null,
          summary: title,
        },
        revision: a.revision,
        agentSession,
        lastProgressAt: 0,
        seenCompletion: false,
      };
      this.sessions.set(id, t);
      this.events.append("agent.started", { machine_id: machine.machine_id, session_id: id, generation: 1, payload: { agent: t.session.agent, project: t.session.project, title } });
    } else if (agentSession && t.agentSession && agentSession !== t.agentSession) {
      // A new agent process in the same pane is a new generation; older events and approvals are void.
      this.closeApproval(t.session, "expired");
      t.session.generation++;
      t.seenCompletion = false;
      this.events.append("agent.started", { machine_id: machine.machine_id, session_id: id, generation: t.session.generation, payload: { agent: t.session.agent, project: t.session.project, title, restarted: true } });
    }

    const s = t.session;
    t.agentSession = agentSession ?? t.agentSession;
    s.stale = false;
    s.title = title || s.title;
    s.summary = s.summary || s.title;
    s.cwd = cwd ?? s.cwd;
    s.project = s.cwd ? path.basename(s.cwd) : s.project;
    s.name = a.name ?? s.name;
    s.agent = a.agent ?? s.agent;

    let next: AgentState;
    switch (a.agent_status) {
      case "blocked":
        next = await this.ensureApproval(adapter, t);
        break;
      case "working":
        next = "working";
        break;
      case "done":
        next = t.seenCompletion ? "idle" : "completed_unseen";
        break;
      default:
        next = "idle";
    }
    if (a.agent_status !== "blocked") this.closeApproval(s, "resolved");
    if (a.agent_status === "working") t.seenCompletion = false;

    const changed = this.transition(t, next, a.agent_status);
    if (!changed && next === "working" && a.revision !== t.revision && Date.now() - t.lastProgressAt > PROGRESS_THROTTLE_MS) {
      t.lastProgressAt = Date.now();
      this.events.append("agent.progress", { machine_id: s.machine_id, session_id: id, generation: s.generation, payload: { summary: s.title } });
    }
    t.revision = a.revision;
  }

  private transition(t: Tracked, next: AgentState, herdrStatus: Session["herdr_status"]): boolean {
    const s = t.session;
    s.herdr_status = herdrStatus;
    if (s.state === next) return false;
    const prev = s.state;
    s.state = next;
    s.priority = STATE_PRIORITY[next];
    s.state_since = new Date().toISOString();
    const base = { machine_id: s.machine_id, session_id: s.session_id, generation: s.generation };
    if (next === "blocked" || next === "approval_required")
      this.events.append("agent.blocked", { ...base, payload: { summary: this.approvals.get(s.pending_approval_id ?? "")?.question ?? "Needs input", approval_id: s.pending_approval_id, from: prev } });
    else if (next === "completed_unseen") this.events.append("agent.completed", { ...base, payload: { summary: s.title, from: prev } });
    else if (next === "failed") this.events.append("agent.failed", { ...base, payload: { summary: s.title, from: prev } });
    else if (next === "working") this.events.append("agent.progress", { ...base, payload: { summary: s.title, from: prev } });
    else if (next === "idle") this.events.append("agent.idle", { ...base, payload: { from: prev } });
    if (next === "working") t.lastProgressAt = Date.now();
    return true;
  }

  private markStale(t: Tracked) {
    t.session.stale = true;
    // Do not fake a running state: an unreachable host's agents are shown as disconnected,
    // and the last-known state stays visible via state_since/herdr_status.
    t.session.state = "disconnected";
    t.session.priority = STATE_PRIORITY.disconnected;
  }

  private async ensureApproval(adapter: HerdrAdapter, t: Tracked): Promise<AgentState> {
    const s = t.session;
    const open = s.pending_approval_id ? this.approvals.get(s.pending_approval_id) : null;
    let screen: string;
    try {
      screen = (await adapter.read(paneOf(s.session_id), 60, { source: "visible" })).text;
    } catch {
      return open ? (open.options.length ? "approval_required" : "blocked") : "blocked";
    }
    const parsed = parseDecision(screen);
    if (open && open.evidence_fingerprint === parsed.fingerprint && open.generation === s.generation) return open.options.length ? "approval_required" : "blocked";
    if (open) this.closeApproval(s, "expired");
    const approval: Approval = {
      approval_id: "apr_" + randomBytes(8).toString("hex"),
      session_id: s.session_id,
      generation: s.generation,
      question: parsed.question,
      options: parsed.options,
      evidence_fingerprint: parsed.fingerprint,
      evidence: parsed.evidence,
      parse: parsed.parse,
      status: "open",
      created_at: new Date().toISOString(),
    };
    this.approvals.set(approval.approval_id, approval);
    s.pending_approval_id = approval.approval_id;
    s.summary = parsed.question;
    this.events.append("approval.requested", {
      machine_id: s.machine_id,
      session_id: s.session_id,
      generation: s.generation,
      payload: { approval_id: approval.approval_id, question: approval.question, options: approval.options.map((o) => o.label), parse: approval.parse },
    });
    return approval.options.length ? "approval_required" : "blocked";
  }

  closeApproval(s: Session, status: "resolved" | "expired") {
    if (!s.pending_approval_id) return;
    const a = this.approvals.get(s.pending_approval_id);
    s.pending_approval_id = null;
    s.summary = s.title;
    if (!a || a.status !== "open") return;
    a.status = status;
    this.events.append("approval.resolved", { machine_id: s.machine_id, session_id: s.session_id, generation: a.generation, payload: { approval_id: a.approval_id, status } });
  }
}

export function paneOf(sessionId: string) {
  return sessionId.slice(sessionId.lastIndexOf("/") + 1);
}
