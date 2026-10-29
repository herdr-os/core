// Versioned companion contract shared with the R1 client. Mirrors protocol/openapi.yaml.

export const PROTOCOL_VERSION = 1;

export type AgentKind = "claude" | "codex" | "pi" | string;

/** Herdr's own lifecycle vocabulary (herdr 0.9 socket API). */
export type HerdrStatus = "idle" | "working" | "blocked" | "done" | "unknown";

/** Bridge-level actionable state; the deck sorts by this, highest first. */
export type AgentState =
  | "approval_required"
  | "blocked"
  | "failed"
  | "completed_unseen"
  | "working"
  | "idle"
  | "disconnected";

export const STATE_PRIORITY: Record<AgentState, number> = {
  approval_required: 60,
  blocked: 50,
  failed: 40,
  completed_unseen: 30,
  working: 20,
  idle: 10,
  disconnected: 0,
};

export interface Machine {
  machine_id: string;
  label: string;
  kind: "local" | "ssh";
  status: "connected" | "disconnected";
  last_seen_at: string | null;
  herdr_version: string | null;
}

/** One coding-agent session, qualified by machine so pane IDs never collide across hosts. */
export interface Session {
  session_id: string;
  machine_id: string;
  machine_label: string;
  agent: AgentKind;
  name: string | null;
  title: string;
  project: string;
  cwd: string | null;
  state: AgentState;
  herdr_status: HerdrStatus;
  priority: number;
  generation: number;
  state_since: string;
  stale: boolean;
  pending_approval_id: string | null;
  summary: string;
}

export type EventType =
  | "agent.started"
  | "agent.progress"
  | "agent.blocked"
  | "agent.failed"
  | "agent.completed"
  | "agent.idle"
  | "agent.removed"
  | "terminal.output"
  | "changes.updated"
  | "approval.requested"
  | "approval.resolved"
  | "machine.connected"
  | "machine.disconnected"
  | "action.receipt";

export interface BridgeEvent {
  event_id: string;
  cursor: string;
  occurred_at: string;
  machine_id: string | null;
  session_id: string | null;
  generation: number | null;
  type: EventType;
  payload: Record<string, unknown>;
}

export type ReceiptStatus = "pending" | "accepted" | "rejected" | "unknown";

export interface ActionReceipt {
  action_id: string;
  idempotency_key: string;
  device_id: string;
  kind: string;
  scope: Record<string, unknown>;
  status: ReceiptStatus;
  detail: string | null;
  created_at: string;
  updated_at: string;
  result: Record<string, unknown> | null;
}

export type Scope = "read" | "act" | "review" | "terminal_write" | "admin";

export interface Approval {
  approval_id: string;
  session_id: string;
  generation: number;
  question: string;
  options: { index: number; label: string; key: string }[];
  /** Hash of the terminal evidence the question was parsed from; responses are bound to it. */
  evidence_fingerprint: string;
  evidence: string;
  parse: "structured" | "heuristic" | "free_text";
  status: "open" | "resolved" | "expired";
  created_at: string;
}

/**
 * What an agent's own session file says beyond Herdr's view: its model, how full its context is,
 * the subagents and background commands it started, its last reply, and a usage-limit stop.
 */
export interface Insight {
  session_id: string;
  harness: string;
  model: string | null;
  effort: string | null;
  /** Tokens the last request sent, and the model's window when the harness records it. */
  context: { used: number; window: number | null } | null;
  subagents: Subagent[];
  tasks: BackgroundTask[];
  /** What the agent is doing now: its latest tool step, e.g. "Bash: Run the device check" (Claude Code). */
  step: string | null;
  last_reply: { text: string; at: string } | null;
  limit: { message: string; at: string } | null;
  /** The last ~30 turns of the conversation, oldest first, without tool calls or results. */
  messages: Message[];
  read_at: string;
}

export interface Message {
  role: "you" | "agent";
  text: string;
  at: string;
  /** Path of a photo the R1 sent, on the agent's machine; [text] is then what you said about it. */
  photo: string | null;
}

export interface Subagent {
  id: string;
  description: string;
  type: string | null;
  state: "working" | "done" | "failed";
  started_at: string;
  ended_at: string | null;
  /** From the subagent's own transcript (Claude Code): its latest tool step or words, model, context and last write. */
  step: string | null;
  model: string | null;
  context_used: number | null;
  active_at: string | null;
}

export interface BackgroundTask {
  id: string;
  command: string;
  state: "running" | "done" | "failed" | "stopped";
  started_at: string;
  ended_at: string | null;
  exit_code: number | null;
  /** The last lines of the command's output file, sanitized and redacted, when the harness keeps one. */
  tail: string[];
}

export interface ApiError {
  error: { code: string; message: string; details?: Record<string, unknown> };
}
