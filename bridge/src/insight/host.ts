import { spawn } from "node:child_process";
import type { Insight, Session } from "../protocol.ts";
import { sanitizeTerminal } from "../terminal/sanitize.ts";
import { lines, messageReaders, readers, type TaskRead } from "./parse.ts";

/** Runs a shell script on one machine and returns its stdout. */
export type Run = (script: string) => Promise<string>;

const TAIL = 3_000_000;
/** Sections after the session file (subagent transcripts, command output) start with this line. */
const SEP = "\u001e";

function sh(cmd: string, args: string[], script: string, timeoutMs = 8000): Promise<string> {
  return new Promise((resolve, reject) => {
    const p = spawn(cmd, args, { stdio: ["pipe", "pipe", "ignore"] });
    const out: Buffer[] = [];
    const timer = setTimeout(() => p.kill(), timeoutMs);
    p.stdout.on("data", (b: Buffer) => out.push(b));
    p.on("error", reject);
    p.on("close", (code) => {
      clearTimeout(timer);
      code === 0 ? resolve(Buffer.concat(out).toString("utf8")) : reject(new Error(`${cmd} exited ${code}`));
    });
    p.stdin.end(script);
  });
}

export const localRun: Run = (script) => sh("sh", ["-s"], script);
export const sshRun = (target: string): Run => (script) => sh("ssh", ["-o", "BatchMode=yes", "-o", "ConnectTimeout=8", target, "sh", "-s"], script);

/** SSH targets Herdr already knows, by machine label. */
export async function herdrTargets(bin: string): Promise<Map<string, string>> {
  const out = await sh(bin, ["machine", "list", "--json"], "");
  return new Map((JSON.parse(out) as { label: string; target: string }[]).map((m) => [m.label, m.target]));
}

/**
 * The script that prints the tail of an agent's session file. Values come from Herdr, so they are
 * checked before they reach the shell: ids are plain characters, paths sit in a harness's own folder.
 */
/**
 * OpenCode keeps sessions in SQLite and Herdr reports no id for them, so the newest top-level
 * session in the agent's folder stands in. Each message comes out as one JSON line, trimmed to what
 * the reader uses (text, and task tools for subagents).
 */
function opencodeScript(cwd: string): string | null {
  if (!cwd.startsWith("/") || /['"\\\n$`]/.test(cwd)) return null;
  const j = (p: string) => `json_extract(p.data,'$.${p}')`;
  const part = `case ${j("type")} when 'text' then json_object('type','text','text',substr(${j("text")},1,2000),'synthetic',${j("synthetic")})
    else json_object('type','tool','id',${j("callID")},'status',${j("state.status")},'description',${j("state.input.description")},'agent',${j("state.input.subagent_type")},'start',${j("state.time.start")},'end',${j("state.time.end")}) end`;
  const msg = (f: string) => `json_extract(m.data,'$.${f}')`;
  return `db="\${XDG_DATA_HOME:-$HOME/.local/share}/opencode/opencode.db"; [ -f "$db" ] && sqlite3 -readonly "$db" <<'HERDR_SQL'
select json_object('type','oc','at',m.time_created,
  'msg',json_object('role',${msg("role")},'modelID',${msg("modelID")},'variant',${msg("variant")},'tokens',json(${msg("tokens")}),'error',json(${msg("error")})),
  'parts',(select json_group_array(json(${part})) from part p where p.message_id=m.id and (${j("type")}='text' or (${j("type")}='tool' and ${j("tool")}='task'))))
from (select * from message where session_id=(select id from session where directory='${cwd}' and parent_id is null and time_archived is null order by time_updated desc limit 1)
  order by time_created desc limit 80) m order by m.time_created;
HERDR_SQL`;
}

export function locate(agent: string, handle: string): string | null {
  if (agent === "opencode") return opencodeScript(handle);
  // Claude Code keeps each subagent's transcript beside the session file; the newest eight follow it.
  const subs = agent === "claude"
    ? `; for m in $(ls -t "\${f%.jsonl}"/subagents/agent-*.meta.json 2>/dev/null | head -8); do printf '\\n\\036\\n'; cat "$m"; printf '\\n'; tail -c 65536 "\${m%.meta.json}.jsonl" 2>/dev/null; done; true`
    : "";
  if (handle.startsWith("/")) {
    if (!/\/\.(pi\/agent\/sessions|claude\/projects|codex\/sessions)\//.test(handle) || /['\\\n]|\.\.\//.test(handle)) return null;
    return subs ? `f='${handle}'; tail -c ${TAIL} "$f"${subs}` : `tail -c ${TAIL} '${handle}'`;
  }
  if (!/^[A-Za-z0-9._-]+$/.test(handle)) return null;
  // Globbed rather than derived from the cwd: the agent may have moved since it started.
  const glob = agent === "claude" ? `"$HOME"/.claude/projects/*/${handle}.jsonl` : agent === "codex" ? `"$HOME"/.codex/sessions/*/*/*/*${handle}.jsonl` : null;
  return glob && `f=$(ls ${glob} 2>/dev/null | head -1); [ -n "$f" ] && tail -c ${TAIL} "$f"${subs}`;
}

/** Claude Code writes background command output under its temp folder; nothing else is read. */
const OUTPUT = /^\/(?:private\/)?tmp\/claude-\d+\/(?:[A-Za-z0-9._-]+\/)+tasks\/[A-Za-z0-9_-]+\.output$/;

/** The script that prints the end of each command's output file, each after a separator line naming it. */
export function outputs(tasks: TaskRead[]): string | null {
  const parts = tasks.filter((t) => t.output_file && OUTPUT.test(t.output_file) && !t.output_file.includes("/../"))
    .map((t) => `printf '\\036%s\\n' '${t.id.replace(/[^A-Za-z0-9_-]/g, "")}'; tail -c 4096 '${t.output_file}' 2>/dev/null; printf '\\n'`);
  return parts.length ? parts.join("; ") : null;
}

/** The last [n] non-blank output lines per task id, escapes stripped and secrets redacted. */
export function tails(out: string, n = 3): Map<string, string[]> {
  return new Map(out.split(SEP).slice(1).map((chunk) => {
    const nl = chunk.indexOf("\n");
    const shown = sanitizeTerminal(chunk.slice(nl + 1)).lines.filter((l) => l.text.trim()).slice(-n);
    return [chunk.slice(0, nl), shown.map((l) => l.text.trimEnd())];
  }));
}

/** Reads what each agent's own session file says, a few seconds fresh at most. */
export class Insights {
  private cache = new Map<string, { at: number; value: Promise<Insight | null> }>();
  readonly runFor: (machineId: string) => Run | null;
  private readonly handleOf: (sessionId: string) => string | null;

  constructor(runFor: (machineId: string) => Run | null, handleOf: (sessionId: string) => string | null) {
    this.runFor = runFor;
    this.handleOf = handleOf;
  }

  get(s: Session): Promise<Insight | null> {
    const hit = this.cache.get(s.session_id);
    if (hit && Date.now() - hit.at < 4000) return hit.value;
    const value = this.read(s).catch(() => null);
    this.cache.set(s.session_id, { at: Date.now(), value });
    return value;
  }

  private async read(s: Session): Promise<Insight | null> {
    const reader = readers[s.agent];
    const handle = s.agent === "opencode" ? s.cwd : this.handleOf(s.session_id);
    const run = this.runFor(s.machine_id);
    const script = reader && handle && run ? locate(s.agent, handle) : null;
    if (!script) return null;
    const [main, ...subs] = (await run!(script)).split(`\n${SEP}\n`);
    const ls = lines(main);
    const r = reader(ls, subs.map(lines));
    const tasks = r.tasks as TaskRead[];
    // Running commands, and the last three that ended, get their output tail.
    const watched = [...tasks.filter((t) => t.state === "running"), ...tasks.filter((t) => t.state !== "running").slice(-3)];
    const tailScript = outputs(watched);
    const got = tailScript ? tails(await run!(tailScript).catch(() => "")) : new Map<string, string[]>();
    r.tasks = tasks.map(({ output_file: _, ...t }) => ({ ...t, tail: got.get(t.id) ?? t.tail }));
    return { session_id: s.session_id, harness: s.agent, ...r, messages: messageReaders[s.agent]?.(ls) ?? [], read_at: new Date().toISOString() };
  }
}
