import net from "node:net";
import { spawn } from "node:child_process";

/** Error returned by the Herdr server (`{error:{code,message}}`), e.g. `agent_blocked`. */
export class HerdrError extends Error {
  readonly code: string;
  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}

export interface HerdrTransport {
  request<T>(method: string, params: Record<string, unknown>, timeoutMs?: number): Promise<T>;
}

let seq = 0;

/**
 * Herdr 0.9 speaks newline-delimited JSON over a Unix socket, one request per connection
 * (the server closes after replying).
 */
export class SocketTransport implements HerdrTransport {
  readonly path: string;
  constructor(path: string) {
    this.path = path;
  }

  request<T>(method: string, params: Record<string, unknown>, timeoutMs = 5000): Promise<T> {
    const id = `bridge:${++seq}`;
    return new Promise((resolve, reject) => {
      const sock = net.createConnection(this.path);
      let buf = "";
      let settled = false;
      const done = (fn: () => void) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        sock.destroy();
        fn();
      };
      const timer = setTimeout(() => done(() => reject(new HerdrError("timeout", `${method} timed out`))), timeoutMs);
      sock.on("error", (e) => done(() => reject(new HerdrError("unreachable", e.message))));
      sock.on("connect", () => sock.write(JSON.stringify({ id, method, params }) + "\n"));
      sock.on("data", (chunk) => {
        buf += chunk;
        const nl = buf.indexOf("\n");
        if (nl < 0) return;
        done(() => settle(buf.slice(0, nl), resolve, reject));
      });
      sock.on("close", () => {
        if (buf.trim()) done(() => settle(buf, resolve, reject));
        else done(() => reject(new HerdrError("closed", `${method}: connection closed without reply`)));
      });
    });
  }
}

/**
 * Reaches a saved SSH machine through `herdr --machine <label> api ...` forwarding.
 * Herdr's own CLI owns SSH auth; the bridge never holds remote credentials.
 */
export class MachineCliTransport implements HerdrTransport {
  readonly bin: string;
  readonly machine: string;
  constructor(bin: string, machine: string) {
    this.bin = bin;
    this.machine = machine;
  }

  request<T>(method: string, params: Record<string, unknown>, timeoutMs = 15000): Promise<T> {
    const args = cliArgs(method, params);
    if (!args) return Promise.reject(new HerdrError("unsupported", `${method} is not forwarded for SSH machines`));
    return new Promise((resolve, reject) => {
      const child = spawn(this.bin, ["--machine", this.machine, ...args], { stdio: ["ignore", "pipe", "pipe"] });
      let out = "";
      let err = "";
      const timer = setTimeout(() => child.kill("SIGTERM"), timeoutMs);
      child.stdout.on("data", (d) => (out += d));
      child.stderr.on("data", (d) => (err += d));
      child.on("error", (e) => reject(new HerdrError("unreachable", e.message)));
      child.on("close", (code) => {
        clearTimeout(timer);
        if (code === 0) return settle(out.trim(), resolve, reject);
        settle(err.trim() || out.trim(), resolve, reject, `exit ${code}`);
      });
    });
  }
}

function settle<T>(line: string, resolve: (v: T) => void, reject: (e: Error) => void, fallback = "bad reply") {
  let msg: { result?: T; error?: { code: string; message: string } };
  try {
    msg = JSON.parse(line);
  } catch {
    return reject(new HerdrError("protocol", `${fallback}: ${line.slice(0, 200)}`));
  }
  if (msg.error) return reject(new HerdrError(msg.error.code, msg.error.message));
  resolve(msg.result as T);
}

/** Maps the socket methods the adapter uses onto their forwarded CLI equivalents. */
function cliArgs(method: string, p: Record<string, unknown>): string[] | null {
  switch (method) {
    case "ping":
    case "agent.list":
      return ["agent", "list"];
    case "agent.read":
      return ["agent", "read", String(p.target), "--source", String(p.source).replace("_", "-"), "--lines", String(p.lines ?? 200)];
    case "agent.prompt":
      return ["agent", "prompt", String(p.target), String(p.text)];
    case "agent.send_keys":
      return ["agent", "send-keys", String(p.target), ...(p.keys as string[])];
    default:
      return null;
  }
}
