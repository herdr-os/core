#!/usr/bin/env node
import { mkdirSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { parseArgs } from "node:util";
import { listen } from "./api/server.ts";
import { Devices } from "./auth/devices.ts";
import { EventLog } from "./events/log.ts";
import { localAdapter, sshAdapter, type HerdrAdapter } from "./herdr-adapter/adapter.ts";
import { demoPanes, FixtureAdapter } from "./herdr-adapter/fixture.ts";
import { Herd } from "./index/herd.ts";
import { herdrTargets, Insights, localRun, sshRun, type Run } from "./insight/host.ts";
import { fixtureInsights } from "./insight/fixture.ts";
import { Receipts } from "./receipts/receipts.ts";
import { openStore } from "./store.ts";
import type { Scope } from "./protocol.ts";
import { makeDemoRepos } from "./demo.ts";
import { CODE_TTL_MS, pairingLink } from "./auth/devices.ts";
import { spawnSync } from "node:child_process";

const { values, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    host: { type: "string", default: "127.0.0.1" },
    port: { type: "string", default: "8787" },
    data: { type: "string", default: path.join(os.homedir(), ".config", "herdr-bridge") },
    socket: { type: "string", default: process.env.HERDR_SOCKET_PATH ?? path.join(os.homedir(), ".config", "herdr", "herdr.sock") },
    herdr: { type: "string", default: process.env.HERDR_BIN_PATH ?? "herdr" },
    label: { type: "string", default: os.hostname().replace(/\.local$/, "") },
    machine: { type: "string", multiple: true, default: [] },
    "tls-cert": { type: "string" },
    "tls-key": { type: "string" },
    "private-network": { type: "boolean", default: false },
    fixtures: { type: "boolean", default: false },
    scopes: { type: "string", default: "read,act,review" },
    qr: { type: "boolean", default: false },
    url: { type: "string" },
    tailscale: { type: "boolean", default: false },
  },
});

// `tailscale ip` still prints an address while Tailscale is stopped, so ask for the backend state too.
function tailnetIp(): string {
  const r = spawnSync("tailscale", ["status", "--json"], { encoding: "utf8" });
  const s = r.status === 0 ? JSON.parse(r.stdout) : null;
  const ip = s?.BackendState === "Running" ? (s.Self?.TailscaleIPs as string[] | undefined)?.find((a) => a.includes(".")) : undefined;
  if (!ip) {
    console.error("--tailscale: Tailscale isn't running on this machine. Start it (`tailscale up`) and try again.");
    process.exit(2);
  }
  return ip;
}
if (values.tailscale) {
  const ip = tailnetIp();
  values.host = ip;
  values["private-network"] = true;
  values.url ??= `http://${ip}:${values.port}`;
}

const cmd = positionals[0] ?? "serve";
mkdirSync(values.data!, { recursive: true, mode: 0o700 });
const db = openStore(path.join(values.data!, values.fixtures ? "bridge-fixtures.db" : "bridge.db"));
const devices = new Devices(db);

switch (cmd) {
  case "enroll": {
    const scopes = values.scopes!.split(",") as Scope[];
    const minted = Date.now();
    const code = devices.mintCode(scopes, minted);
    console.log(`Enrollment code: ${code}  (single use, expires in 10 minutes)`);
    console.log(`Scopes: ${values.scopes}`);
    if (values.qr) {
      if (!values.url) {
        console.error("--qr needs --url (the address the R1 uses to reach this bridge) or --tailscale");
        process.exit(2);
      }
      const link = pairingLink(values.url, code, scopes, new Date(minted + CODE_TTL_MS).toISOString());
      // ponytail: renders through the optional `qrencode` CLI rather than bundling a QR encoder.
      const qr = spawnSync("qrencode", ["-t", "ansiutf8", "-m", "2", link], { encoding: "utf8" });
      if (qr.status === 0) console.log(qr.stdout);
      else console.log("(Install qrencode to show this as a QR code: brew install qrencode)");
      console.log(`Pairing link: ${link}`);
      console.log("On the R1: Pair a host → scan the code.");
    }
    break;
  }
  case "devices": {
    for (const d of devices.list()) console.log(`${d.device_id}  ${d.name}  [${d.scopes.join(",")}]  last seen ${d.last_seen_at ?? "never"}${d.revoked_at ? "  REVOKED" : ""}`);
    break;
  }
  case "revoke": {
    const id = positionals[1];
    console.log(id && devices.revoke(id) ? `Revoked ${id}. Its token no longer reads or mutates anything.` : `No active device ${id}`);
    break;
  }
  case "serve": {
    const loopback = ["127.0.0.1", "::1", "localhost"].includes(values.host!);
    const tls = Boolean(values["tls-cert"] && values["tls-key"]);
    if (!loopback && !tls && !values["private-network"]) {
      console.error("Refusing to serve plain HTTP on a non-loopback address. Pass --tls-cert/--tls-key, or --private-network if this interface is a Tailscale/WireGuard tunnel.");
      process.exit(2);
    }
    const events = new EventLog(db);
    let adapters: HerdrAdapter[];
    if (values.fixtures) {
      const repos = await makeDemoRepos(path.join(values.data!, "demo-repos"));
      adapters = [new FixtureAdapter("local", "MacBook Pro", demoPanes((n) => repos[n]))];
    } else {
      adapters = [localAdapter(values.socket!, values.label!), ...values.machine!.map((m) => sshAdapter(values.herdr!, m))];
    }
    const herd = new Herd(adapters, events);
    herd.start();
    const targets = values.machine!.length ? await herdrTargets(values.herdr!).catch(() => new Map<string, string>()) : new Map<string, string>();
    const runs = new Map<string, Run>();
    for (const a of values.fixtures ? [] : adapters) {
      const target = a.kind === "local" ? null : targets.get(a.label);
      if (a.kind === "local") runs.set(a.machineId, localRun);
      else if (target) runs.set(a.machineId, sshRun(target));
    }
    const demo = values.fixtures ? fixtureInsights(herd) : null;
    if (demo) for (const a of adapters) runs.set(a.machineId, demo.run);
    const insights = new Insights((m) => runs.get(m) ?? null, demo?.handle ?? ((id) => herd.agentSession(id)));
    const server = await listen({ db, devices, events, herd, insights, receipts: new Receipts(db) }, { host: values.host!, port: Number(values.port), tlsCert: values["tls-cert"], tlsKey: values["tls-key"] });
    const addr = server.address();
    console.log(`herdr-bridge ${values.fixtures ? "(fixtures) " : ""}listening on ${tls ? "https" : "http"}://${values.host}:${typeof addr === "object" && addr ? addr.port : values.port}`);
    console.log(`machines: ${adapters.map((a) => a.label).join(", ")}`);
    const stop = () => {
      herd.stop();
      server.close(() => process.exit(0));
      setTimeout(() => process.exit(0), 1000).unref();
    };
    process.on("SIGINT", stop);
    process.on("SIGTERM", stop);
    break;
  }
  default:
    console.error("usage: herdr-bridge [serve|enroll|devices|revoke <device_id>] [--fixtures] [--tailscale] [--machine <label>]...");
    process.exit(2);
}
