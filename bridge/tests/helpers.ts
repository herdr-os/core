import { mkdtempSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import type { AddressInfo } from "node:net";
import { listen } from "../src/api/server.ts";
import { Devices } from "../src/auth/devices.ts";
import { EventLog } from "../src/events/log.ts";
import { demoPanes, FixtureAdapter } from "../src/herdr-adapter/fixture.ts";
import { Herd } from "../src/index/herd.ts";
import { Receipts } from "../src/receipts/receipts.ts";
import { openStore } from "../src/store.ts";
import { makeDemoRepos } from "../src/demo.ts";
import type { Scope } from "../src/protocol.ts";
import { Insights } from "../src/insight/host.ts";

let n = 0;
export const key = () => `test-key-${Date.now()}-${++n}`;

export async function bridge(opts: { scopes?: Scope[]; second?: boolean; insights?: Insights } = {}) {
  const tmp = mkdtempSync(path.join(os.tmpdir(), "herdr-bridge-test-"));
  const repos = await makeDemoRepos(path.join(tmp, "repos"));
  const db = openStore(":memory:");
  const devices = new Devices(db);
  const events = new EventLog(db);
  const mac = new FixtureAdapter("local", "MacBook Pro", demoPanes((r) => repos[r]));
  const adapters = [mac];
  let server2: FixtureAdapter | null = null;
  if (opts.second) {
    server2 = new FixtureAdapter("ssh:home-server", "Home Server", [
      { pane_id: "w1:p1", agent: "codex", name: "codex", status: "working", cwd: repos["tests-qa"], title: "Nightly", terminal: ["running"] },
    ]);
    adapters.push(server2 as FixtureAdapter);
  }
  const herd = new Herd(adapters, events);
  const receipts = new Receipts(db);
  for (const a of adapters) await herd.poll(a.machineId);
  const server = await listen({ db, devices, events, herd, insights: opts.insights ?? new Insights(() => null, () => null), receipts }, { host: "127.0.0.1", port: 0 });
  const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  const code = devices.mintCode(opts.scopes ?? ["read", "act", "review"]);
  const claim = await (await fetch(`${base}/v1/enrollments/claim`, { method: "POST", body: JSON.stringify({ code, name: "Test R1" }) })).json();
  const token: string = claim.token;

  const call = async (method: string, p: string, body?: unknown, headers: Record<string, string> = {}) => {
    const res = await fetch(base + p, {
      method,
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json", ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return { status: res.status, body: (await res.json()) as any };
  };
  const act = (p: string, body: unknown, k = key()) => call("POST", p, body, { "Idempotency-Key": k });
  const close = () => new Promise<void>((r) => server.close(() => r()));
  return { base, token, claim, db, devices, events, herd, receipts, mac, server2, repos, call, act, close };
}

export const sid = (pane: string, machine = "local") => encodeURIComponent(`${machine}/${pane}`);
