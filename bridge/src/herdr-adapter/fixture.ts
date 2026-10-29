import type { HerdrStatus } from "../protocol.ts";
import type { HerdrAdapter, HerdrAgent, TerminalRead } from "./adapter.ts";
import { HerdrError } from "./socket.ts";

export interface FixturePane {
  pane_id: string;
  agent: string;
  name: string;
  status: HerdrStatus;
  cwd: string;
  title: string;
  terminal: string[];
}

/**
 * In-memory Herdr for tests and the `--fixtures` demo. Behaves like the real server for
 * the calls the bridge makes, including `agent_blocked` rejection of prompts.
 */
export class FixtureAdapter implements HerdrAdapter {
  readonly machineId: string;
  readonly label: string;
  readonly kind = "local" as const;
  readonly panes = new Map<string, FixturePane & { revision: number; session: string }>();
  online = true;
  readonly sent: { pane: string; kind: "prompt" | "keys"; value: string }[] = [];

  constructor(machineId: string, label: string, panes: FixturePane[]) {
    this.machineId = machineId;
    this.label = label;
    for (const p of panes) this.panes.set(p.pane_id, { ...p, revision: 1, session: `${p.pane_id}#1` });
  }

  set(paneId: string, patch: Partial<FixturePane> & { restart?: boolean }) {
    const p = this.pane(paneId);
    Object.assign(p, patch);
    if (patch.restart) p.session = `${paneId}#${Number(p.session.split("#")[1]) + 1}`;
    p.revision++;
  }

  private pane(id: string) {
    const p = this.panes.get(id);
    if (!p) throw new HerdrError("pane_not_found", `no pane ${id}`);
    return p;
  }

  private check() {
    if (!this.online) return Promise.reject(new HerdrError("unreachable", `${this.label} offline`));
    return Promise.resolve();
  }

  async ping() {
    await this.check();
    return { version: "fixture" };
  }

  async listAgents(): Promise<HerdrAgent[]> {
    await this.check();
    return [...this.panes.values()].map((p) => ({
      pane_id: p.pane_id,
      workspace_id: p.pane_id.split(":")[0],
      agent: p.agent,
      name: p.name,
      agent_status: p.status,
      agent_session: { value: p.session },
      cwd: p.cwd,
      terminal_title_stripped: p.title,
      revision: p.revision,
    }));
  }

  async read(paneId: string, lines: number): Promise<TerminalRead> {
    await this.check();
    const p = this.pane(paneId);
    return { text: p.terminal.slice(-lines).join("\n"), revision: p.revision, truncated: p.terminal.length > lines };
  }

  async prompt(paneId: string, text: string) {
    await this.check();
    const p = this.pane(paneId);
    if (p.status === "blocked") throw new HerdrError("agent_blocked", "agent is waiting at a dialog");
    this.sent.push({ pane: paneId, kind: "prompt", value: text });
    p.terminal.push(`> ${text}`);
    p.status = "working";
    p.revision++;
  }

  async sendKeys(paneId: string, keys: string[]) {
    await this.check();
    const p = this.pane(paneId);
    this.sent.push({ pane: paneId, kind: "keys", value: keys.join(" ") });
    p.revision++;
  }
}

/** The herd from the storyboard: one blocked Claude, a working Codex, an idle Pi. */
export function demoPanes(repoRoot: (name: string) => string): FixturePane[] {
  return [
    {
      pane_id: "w1:p1",
      agent: "claude",
      name: "claude",
      status: "blocked",
      cwd: repoRoot("backend-api"),
      title: "Database migration",
      terminal: [
        "~/api git:(main) ✗ pnpm test",
        "> api@1.0.0 test",
        "> vitest",
        " FAIL  src/user.test.ts (1)",
        "   × should create user (32ms)",
        "     AssertionError: expected 200 to be 201",
        "",
        "╭──────────────────────────────────────────────╮",
        "│ Which migration strategy should I use?       │",
        "│                                              │",
        "│ ❯ 1. Incremental migration                   │",
        "│   2. Full replacement                        │",
        "╰──────────────────────────────────────────────╯",
      ],
    },
    {
      pane_id: "w2:p1",
      agent: "codex",
      name: "codex",
      status: "working",
      cwd: repoRoot("tests-qa"),
      title: "Regression tests",
      terminal: ["• Writing regression tests for auth module", "  └ tests/auth.test.ts (+84)", "• Running pnpm vitest auth"],
    },
    {
      pane_id: "w3:p1",
      agent: "pi",
      name: "pi",
      status: "idle",
      cwd: repoRoot("mobile-app"),
      title: "Mobile App",
      terminal: ["π - mobile-app", "Updated UI components", "✓ 12 files changed"],
    },
  ];
}
