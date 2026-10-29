import { createHash } from "node:crypto";

export interface ParsedDecision {
  question: string;
  options: { index: number; label: string; key: string }[];
  evidence: string;
  fingerprint: string;
  parse: "structured" | "heuristic" | "free_text";
}

const OPTION = /^[\s│┃|]*(?:[❯›>▸●◉]\s*)?(\d{1,2})[.)]\s+(.+?)[\s│┃|]*$/;
const BORDER = /^[\s─━═╭╮╰╯│┃┌┐└┘├┤┬┴┼]*$/;

function strip(line: string) {
  return line.replace(/^[\s│┃|]+/, "").replace(/[\s│┃|]+$/, "");
}

/**
 * Extracts the question and numbered choices from the bottom of a blocked agent's screen
 * (Claude Code and Codex both render `1. Yes / 2. No` style dialogs). This is a heuristic over
 * real terminal text; the raw evidence travels with it and responses are bound to its hash.
 */
export function parseDecision(screen: string): ParsedDecision {
  const lines = screen.replace(/\r/g, "").split("\n");
  while (lines.length && BORDER.test(lines[lines.length - 1])) lines.pop();

  let last = -1;
  for (let i = lines.length - 1; i >= 0; i--) {
    if (OPTION.test(lines[i])) {
      last = i;
      break;
    }
  }

  if (last < 0) {
    const tail = lines.filter((l) => !BORDER.test(l)).slice(-8);
    const evidence = tail.map(strip).join("\n").trim();
    const q = [...tail].reverse().map(strip).find((l) => l.endsWith("?")) ?? tail.map(strip).filter(Boolean).pop() ?? "Agent needs input";
    return { question: q, options: [], evidence, fingerprint: hash(evidence), parse: "free_text" };
  }

  let first = last;
  const found: { index: number; label: string }[] = [];
  for (let i = last; i >= 0; i--) {
    const m = OPTION.exec(lines[i]);
    if (m) {
      found.unshift({ index: Number(m[1]), label: m[2].replace(/\s+\((?:esc|shift\+tab|[a-z])\)$/i, "").trim() });
      first = i;
    } else if (strip(lines[i]) === "" || BORDER.test(lines[i])) {
      if (found.length) continue;
    } else break;
  }

  let question = "";
  const context: string[] = [];
  for (let i = first - 1; i >= 0 && context.length < 6; i--) {
    if (BORDER.test(lines[i])) {
      if (question) break;
      continue;
    }
    const t = strip(lines[i]);
    if (!t) {
      if (question) break;
      continue;
    }
    context.unshift(t);
    if (!question) question = t;
  }
  const sequential = found.every((o, k) => o.index === found[0].index + k);
  const evidence = [...context, ...lines.slice(first, last + 1).map(strip).filter(Boolean)].join("\n");
  return {
    question: question || "Choose an option",
    options: found.map((o) => ({ index: o.index, label: o.label, key: String(o.index) })),
    evidence,
    fingerprint: hash(evidence),
    parse: sequential && question.endsWith("?") ? "structured" : "heuristic",
  };
}

export function hash(s: string) {
  return "sha256:" + createHash("sha256").update(s).digest("hex").slice(0, 32);
}
