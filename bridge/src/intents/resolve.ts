import type { Machine, Session } from "../protocol.ts";

// Turns an utterance into a typed, reviewable intent. Resolution never mutates anything; the
// device shows the destination and the user confirms before `/v1/tasks/delegate` runs.

export type Intent =
  | { kind: "delegate"; session_id: string; instruction: string; agent: string; machine: string; project: string }
  | { kind: "question"; text: string };

export interface Resolution {
  status: "resolved" | "ambiguous" | "no_match" | "not_an_operation";
  intent: Intent | null;
  candidates: { session_id: string; agent: string; machine: string; project: string; title: string; state: string }[];
  reason: string;
  parsed: { agent: string | null; machine: string | null; project: string | null; instruction: string };
}

const AGENT_ALIASES: Record<string, string[]> = {
  claude: ["claude", "claude code", "cloud", "clod"],
  codex: ["codex", "code x", "codecs"],
  pi: ["pi", "pie", "π"],
};

const QUESTION_START = /^(what|why|how|when|where|who|which|is|are|does|do|did|can you explain|explain|tell me|show me)\b/i;
const COMMAND = /^(?:hey\s+)?(?:please\s+)?(?:ask|tell|have|get|let)\s+(.+?)\s+(?:to)\s+(.+)$/i;

const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9π ]+/g, " ").replace(/\s+/g, " ").trim();

export function resolveIntent(utterance: string, sessions: Session[], machines: Machine[], focus?: string | null): Resolution {
  const text = utterance.trim().replace(/[.!]+$/, "");
  const m = COMMAND.exec(text);
  if (!m) {
    if (focus && !QUESTION_START.test(text)) return single(sessions.find((s) => s.session_id === focus), text, { agent: null, machine: null, project: null, instruction: text }, "Sent to the agent you are viewing");
    return {
      status: "not_an_operation",
      intent: { kind: "question", text },
      candidates: [],
      reason: "Sounds like a question, not an instruction. Nothing will be sent to an agent.",
      parsed: { agent: null, machine: null, project: null, instruction: text },
    };
  }

  const target = norm(m[1]);
  const instruction = m[2].trim();
  const agent = Object.entries(AGENT_ALIASES).find(([, al]) => al.some((a) => new RegExp(`(^| )${a}( |$)`).test(target)))?.[0] ?? null;

  const machineMatch = /(?:^| )(?:on|at)(?: my| the)? (.+?)(?: (?:in|for) |$)/.exec(target);
  const machineWords = machineMatch ? machineMatch[1] : null;
  const machine = machineWords ? (machines.find((x) => fuzzy(norm(x.label), machineWords)) ?? null) : null;

  const projectMatch = /(?:^| )(?:in|for)(?: the)? (.+?)(?: (?:repo|project))?$/.exec(target);
  const projectWords = projectMatch ? projectMatch[1] : null;

  const parsed = { agent, machine: machine?.label ?? machineWords, project: projectWords, instruction };
  if (machineWords && !machine) return { status: "no_match", intent: null, candidates: [], reason: `No machine called “${machineWords}”.`, parsed };

  let pool = sessions.filter((s) => !s.stale);
  if (agent) pool = pool.filter((s) => s.agent === agent);
  else {
    const named = pool.filter((s) => s.name && target.split(" ").includes(norm(s.name)));
    if (named.length) pool = named;
  }
  if (machine) pool = pool.filter((s) => s.machine_id === machine.machine_id);
  if (projectWords) pool = pool.filter((s) => fuzzy(norm(s.project), projectWords) || fuzzy(norm(s.title), projectWords));

  if (pool.length === 0) return { status: "no_match", intent: null, candidates: [], reason: "No live agent matches that description.", parsed };
  if (pool.length > 1) {
    const focused = pool.find((s) => s.session_id === focus);
    if (focused) return single(focused, instruction, parsed, "Matched the agent you are viewing");
    return {
      status: "ambiguous",
      intent: null,
      candidates: pool.map((s) => ({ session_id: s.session_id, agent: s.agent, machine: s.machine_label, project: s.project, title: s.title, state: s.state })),
      reason: `${pool.length} agents match. Choose one; nothing was sent.`,
      parsed,
    };
  }
  return single(pool[0], instruction, parsed, "One agent matches");
}

function single(s: Session | undefined, instruction: string, parsed: Resolution["parsed"], reason: string): Resolution {
  if (!s) return { status: "no_match", intent: null, candidates: [], reason: "That agent is gone.", parsed };
  return {
    status: "resolved",
    intent: { kind: "delegate", session_id: s.session_id, instruction, agent: s.agent, machine: s.machine_label, project: s.project },
    candidates: [{ session_id: s.session_id, agent: s.agent, machine: s.machine_label, project: s.project, title: s.title, state: s.state }],
    reason,
    parsed,
  };
}

/** True if every word the user said appears (as a prefix) in the label. */
function fuzzy(label: string, words: string) {
  const lw = label.split(" ");
  return norm(words)
    .split(" ")
    .filter((w) => w && !["my", "the", "a"].includes(w))
    .every((w) => lw.some((l) => l.startsWith(w) || w.startsWith(l)) || label.replace(/ /g, "").includes(w));
}
