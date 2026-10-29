import type { HerdrStatus } from "../protocol.ts";
import { HerdrError, MachineCliTransport, SocketTransport, type HerdrTransport } from "./socket.ts";

/** The subset of Herdr's `AgentInfo` the bridge depends on (herdr protocol 22). */
export interface HerdrAgent {
  pane_id: string;
  workspace_id: string;
  agent: string | null;
  name?: string | null;
  agent_status: HerdrStatus;
  agent_session?: { value: string } | null;
  cwd?: string | null;
  foreground_cwd?: string | null;
  terminal_title_stripped?: string | null;
  title?: string | null;
  revision: number;
  state_change_seq?: number;
  completion_seq?: number | null;
}

export interface TerminalRead {
  text: string;
  revision: number;
  truncated: boolean;
}

/**
 * Versioned seam between the bridge and one Herdr server. Everything Herdr-specific
 * (method names, status vocabulary, key names) stays behind this interface.
 */
export interface HerdrAdapter {
  readonly machineId: string;
  readonly label: string;
  readonly kind: "local" | "ssh";
  ping(): Promise<{ version: string | null }>;
  listAgents(): Promise<HerdrAgent[]>;
  read(paneId: string, lines: number, opts?: { source?: "recent_unwrapped" | "detection" | "visible"; ansi?: boolean }): Promise<TerminalRead>;
  prompt(paneId: string, text: string): Promise<void>;
  sendKeys(paneId: string, keys: string[]): Promise<void>;
}

export class TransportAdapter implements HerdrAdapter {
  readonly machineId: string;
  readonly label: string;
  readonly kind: "local" | "ssh";
  readonly transport: HerdrTransport;

  constructor(machineId: string, label: string, kind: "local" | "ssh", transport: HerdrTransport) {
    this.machineId = machineId;
    this.label = label;
    this.kind = kind;
    this.transport = transport;
  }

  async ping() {
    const r = await this.transport.request<{ version?: string; agents?: unknown }>("ping", {});
    return { version: r.version ?? null };
  }

  async listAgents() {
    const r = await this.transport.request<{ agents: HerdrAgent[] }>("agent.list", {});
    return r.agents.filter((a) => a.agent);
  }

  async read(paneId: string, lines: number, opts: { source?: "recent_unwrapped" | "detection" | "visible"; ansi?: boolean } = {}) {
    const r = await this.transport.request<{ read: TerminalRead }>("agent.read", {
      target: paneId,
      source: opts.source ?? "recent_unwrapped",
      lines,
      format: opts.ansi ? "ansi" : "text",
      strip_ansi: !opts.ansi,
    });
    return r.read;
  }

  async prompt(paneId: string, text: string) {
    await this.transport.request("agent.prompt", { target: paneId, text }, 15000);
  }

  async sendKeys(paneId: string, keys: string[]) {
    await this.transport.request("agent.send_keys", { target: paneId, keys });
  }
}

export function localAdapter(socketPath: string, label: string): HerdrAdapter {
  return new TransportAdapter("local", label, "local", new SocketTransport(socketPath));
}

export function sshAdapter(bin: string, label: string): HerdrAdapter {
  return new TransportAdapter(`ssh:${label}`, label, "ssh", new MachineCliTransport(bin, label));
}

export { HerdrError };
