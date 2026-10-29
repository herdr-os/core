import { execFile } from "node:child_process";
import { createHash } from "node:crypto";
import { readFile, readdir, stat } from "node:fs/promises";
import path from "node:path";
import { redactSecrets } from "../terminal/sanitize.ts";

// Diffs come from Git on the trusted host, never from a model. A revision is HEAD plus the
// exact working-tree delta; its fingerprint is what review approvals bind to.

const MAX_FILE_BYTES = 256 * 1024;
const MAX_FILE_LINES = 3000;
const SENSITIVE = [/(^|\/)\.env(\..*)?$/, /\.(pem|key|p12|pfx|jks|keystore)$/, /(^|\/)id_(rsa|ed25519|ecdsa)(\.pub)?$/, /(^|\/)(credentials|secrets?)(\.[a-z]+)?$/i, /(^|\/)\.npmrc$/, /(^|\/)\.netrc$/];

export const isSensitivePath = (p: string) => SENSITIVE.some((re) => re.test(p));

export type FileStatus = "modified" | "added" | "deleted" | "renamed" | "untracked" | "submodule" | "binary";
export type ContentState = "text" | "binary" | "too_large" | "withheld" | "submodule" | "deleted";

export interface ChangedFile {
  file_id: string;
  path: string;
  old_path: string | null;
  status: FileStatus;
  additions: number;
  deletions: number;
  content: ContentState;
}

export interface Revision {
  repo_root: string;
  repo_id: string;
  base_commit: string | null;
  head: "worktree";
  fingerprint: string;
  files: ChangedFile[];
}

export interface DiffLine {
  kind: "context" | "add" | "del";
  old: number | null;
  new: number | null;
  text: string;
}

export interface Hunk {
  hunk_id: string;
  header: string;
  old_start: number;
  new_start: number;
  lines: DiffLine[];
}

export interface FileDiff {
  file: ChangedFile;
  fingerprint: string;
  hunks: Hunk[];
  redactions: number;
}

export class GitError extends Error {
  readonly code: string;
  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}

export function git(cwd: string, args: string[], maxBuffer = 32 * 1024 * 1024): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile("git", ["-C", cwd, "-c", "core.quotepath=off", ...args], { maxBuffer, encoding: "utf8" }, (err, stdout, stderr) => {
      if (err) reject(new GitError("git_failed", (stderr || err.message).trim()));
      else resolve(stdout);
    });
  });
}

export async function repoRoot(cwd: string): Promise<string> {
  try {
    return (await git(cwd, ["rev-parse", "--show-toplevel"])).trim();
  } catch {
    throw new GitError("not_a_repository", `${cwd} is not inside a Git repository`);
  }
}

const fileId = (p: string) => "f_" + createHash("sha256").update(p).digest("hex").slice(0, 12);

async function baseCommit(root: string): Promise<string | null> {
  try {
    return (await git(root, ["rev-parse", "--verify", "HEAD"])).trim();
  } catch {
    return null;
  }
}

/** Snapshot of HEAD + worktree changes, with a fingerprint that changes if any byte changes. */
export async function revision(cwd: string): Promise<Revision> {
  const root = await repoRoot(cwd);
  const base = await baseCommit(root);
  const against = base ?? (await git(root, ["hash-object", "-t", "tree", "/dev/null"])).trim();

  const [numstat, nameStatus, patch, untrackedRaw] = await Promise.all([
    git(root, ["diff", against, "--numstat", "-M", "-z"]),
    git(root, ["diff", against, "--name-status", "-M", "-z"]),
    git(root, ["diff", against, "--binary", "-M"]),
    git(root, ["ls-files", "--others", "--exclude-standard", "-z"]),
  ]);

  const statusByPath = new Map<string, { status: string; old: string | null }>();
  const ns = nameStatus.split("\0").filter(Boolean);
  for (let i = 0; i < ns.length; ) {
    const code = ns[i++];
    if (code.startsWith("R") || code.startsWith("C")) {
      const old = ns[i++];
      statusByPath.set(ns[i++], { status: code[0], old });
    } else statusByPath.set(ns[i++], { status: code[0], old: null });
  }

  const files: ChangedFile[] = [];
  const parts = numstat.split("\0");
  for (let i = 0; i < parts.length; i++) {
    const head = parts[i];
    if (!head) continue;
    const [add, del, maybePath] = head.split("\t");
    let p = maybePath;
    if (p === "") {
      i += 2;
      p = parts[i];
    }
    const st = statusByPath.get(p) ?? { status: "M", old: null };
    const binary = add === "-" && del === "-";
    files.push(describe(p, st.old, st.status, binary, Number(add) || 0, Number(del) || 0));
  }

  const untracked = untrackedRaw.split("\0").filter(Boolean);
  const untrackedHashes: string[] = [];
  for (const p of untracked) {
    const abs = path.join(root, p);
    const info = await stat(abs).catch(() => null);
    if (!info?.isFile()) continue;
    const buf = info.size <= MAX_FILE_BYTES ? await readFile(abs) : null;
    untrackedHashes.push(`${p}:${info.size}:${buf ? createHash("sha256").update(buf).digest("hex") : info.mtimeMs}`);
    const binary = buf ? buf.includes(0) : false;
    const lines = buf && !binary ? buf.toString("utf8").split("\n").length : 0;
    const f = describe(p, null, "?", binary, lines, 0);
    if (!buf) f.content = "too_large";
    files.push(f);
  }

  files.sort((a, b) => a.path.localeCompare(b.path));
  const fingerprint =
    "rev:" +
    createHash("sha256")
      .update(base ?? "unborn")
      .update("\0")
      .update(patch)
      .update("\0")
      .update(untrackedHashes.join("\n"))
      .digest("hex")
      .slice(0, 40);

  return {
    repo_root: root,
    repo_id: "repo_" + createHash("sha256").update(root).digest("hex").slice(0, 12),
    base_commit: base,
    head: "worktree",
    fingerprint,
    files,
  };
}

function describe(p: string, old: string | null, code: string, binary: boolean, add: number, del: number): ChangedFile {
  const status: FileStatus =
    code === "A" ? "added" : code === "D" ? "deleted" : code === "R" || code === "C" ? "renamed" : code === "?" ? "untracked" : "modified";
  let content: ContentState = "text";
  if (isSensitivePath(p)) content = "withheld";
  else if (binary) content = "binary";
  else if (status === "deleted") content = "deleted";
  else if (add + del > MAX_FILE_LINES) content = "too_large";
  return { file_id: fileId(p), path: p, old_path: old, status: binary && status === "modified" ? "binary" : status, additions: add, deletions: del, content };
}

/** One file's hunks at the given revision. Throws `revision_changed` if the tree moved. */
export async function fileDiff(cwd: string, expected: string | null, id: string): Promise<FileDiff> {
  const rev = await revision(cwd);
  if (expected && expected !== rev.fingerprint) throw new GitError("revision_changed", `revision is now ${rev.fingerprint}`);
  const file = rev.files.find((f) => f.file_id === id);
  if (!file) throw new GitError("file_not_found", `no changed file ${id} at ${rev.fingerprint}`);
  if (file.content !== "text" && file.content !== "deleted") return { file, fingerprint: rev.fingerprint, hunks: [], redactions: 0 };

  let patch: string;
  if (file.status === "untracked") {
    const body = await readFile(path.join(rev.repo_root, file.path), "utf8");
    const lines = body.endsWith("\n") ? body.slice(0, -1).split("\n") : body.split("\n");
    patch = `@@ -0,0 +1,${lines.length} @@\n` + lines.map((l) => "+" + l).join("\n");
  } else {
    const against = rev.base_commit ?? (await git(rev.repo_root, ["hash-object", "-t", "tree", "/dev/null"])).trim();
    const args = ["diff", against, "-M", "--no-color", "--"];
    if (file.old_path) args.push(file.old_path);
    args.push(file.path);
    patch = await git(rev.repo_root, args);
  }
  const { hunks, redactions } = parseHunks(patch);
  return { file, fingerprint: rev.fingerprint, hunks, redactions };
}

export function parseHunks(patch: string): { hunks: Hunk[]; redactions: number } {
  const hunks: Hunk[] = [];
  let redactions = 0;
  let cur: Hunk | null = null;
  let o = 0;
  let n = 0;
  for (const line of patch.split("\n")) {
    const h = /^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)$/.exec(line);
    if (h) {
      o = Number(h[1]);
      n = Number(h[2]);
      cur = { hunk_id: `h${hunks.length + 1}`, header: line, old_start: o, new_start: n, lines: [] };
      hunks.push(cur);
      continue;
    }
    if (!cur || line.startsWith("\\")) continue;
    const r = redactSecrets(line.slice(1));
    redactions += r.count;
    if (line[0] === "+") cur.lines.push({ kind: "add", old: null, new: n++, text: r.text });
    else if (line[0] === "-") cur.lines.push({ kind: "del", old: o++, new: null, text: r.text });
    else if (line[0] === " ") cur.lines.push({ kind: "context", old: o++, new: n++, text: r.text });
  }
  return { hunks, redactions };
}

export interface FileEntry {
  name: string;
  path: string;
  type: "dir" | "file";
  git: "M" | "A" | "D" | "?" | null;
  withheld: boolean;
}

/** Lists one directory of the session's repository; never escapes the repo root. */
export async function listFiles(cwd: string, rel: string): Promise<{ root: string; path: string; entries: FileEntry[] }> {
  const rev = await revision(cwd);
  const target = path.resolve(rev.repo_root, rel || ".");
  if (target !== rev.repo_root && !target.startsWith(rev.repo_root + path.sep)) throw new GitError("forbidden_path", "path escapes repository");
  const changed = new Map(rev.files.map((f) => [f.path, f.status]));
  const dirents = await readdir(target, { withFileTypes: true });
  const entries: FileEntry[] = [];
  for (const d of dirents) {
    if (d.name === ".git") continue;
    const p = path.relative(rev.repo_root, path.join(target, d.name));
    const st = changed.get(p);
    const dirty = d.isDirectory() && [...changed.keys()].some((c) => c.startsWith(p + "/"));
    entries.push({
      name: d.name,
      path: p,
      type: d.isDirectory() ? "dir" : "file",
      git: st === "untracked" ? "?" : st === "added" ? "A" : st === "deleted" ? "D" : st || dirty ? "M" : null,
      withheld: isSensitivePath(p),
    });
  }
  entries.sort((a, b) => (a.type === b.type ? a.name.localeCompare(b.name) : a.type === "dir" ? -1 : 1));
  return { root: path.basename(rev.repo_root), path: path.relative(rev.repo_root, target), entries };
}
