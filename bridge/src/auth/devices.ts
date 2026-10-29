import { createHash, randomBytes, randomInt, timingSafeEqual } from "node:crypto";
import type { Scope } from "../protocol.ts";
import type { Store } from "../store.ts";

// Enrollment: the owner mints a short-lived one-time code on the bridge host; the R1 claims it
// over TLS and receives a device-scoped bearer token. Only hashes are stored. No SMS, no host
// Herdr credentials or model keys ever reach the device.

/**
 * What an enrollment QR code carries: the bridge address and a one-time code, nothing reusable.
 * The scopes and expiry ride along so the R1 can say what it is agreeing to before it claims.
 */
export function pairingLink(url: string, code: string, scopes?: Scope[], expiresAt?: string): string {
  const u = new URL(url);
  if (u.protocol !== "https:" && u.protocol !== "http:") throw new Error("Bridge URL must be http(s)");
  const extra = (scopes ? `&s=${encodeURIComponent(scopes.join(","))}` : "") + (expiresAt ? `&x=${encodeURIComponent(expiresAt)}` : "");
  return `herdr://pair?u=${encodeURIComponent(u.origin + u.pathname.replace(/\/$/, ""))}&c=${encodeURIComponent(code)}${extra}`;
}

export const DEFAULT_SCOPES: Scope[] = ["read", "act", "review"];
export const CODE_TTL_MS = 10 * 60 * 1000;

const sha = (s: string) => createHash("sha256").update(s).digest("hex");

export interface Device {
  device_id: string;
  name: string;
  scopes: Scope[];
  machines: string[] | "*";
  created_at: string;
  last_seen_at: string | null;
  revoked_at: string | null;
}

export class Devices {
  readonly db: Store;
  constructor(db: Store) {
    this.db = db;
  }

  /** Returns a code like `4821-0937`, valid once for ten minutes. */
  mintCode(scopes: Scope[] = DEFAULT_SCOPES, now = Date.now()): string {
    const code = `${String(randomInt(0, 10000)).padStart(4, "0")}-${String(randomInt(0, 10000)).padStart(4, "0")}`;
    this.db
      .prepare("INSERT INTO enrollments (code_hash, scopes, expires_at) VALUES (?, ?, ?)")
      .run(sha(code), scopes.join(","), new Date(now + CODE_TTL_MS).toISOString());
    return code;
  }

  claim(code: string, name: string, now = Date.now()): { device: Device; token: string } | null {
    const row = this.db.prepare("SELECT * FROM enrollments WHERE code_hash = ?").get(sha(code.trim())) as
      | { code_hash: string; scopes: string; expires_at: string; claimed_at: string | null }
      | undefined;
    if (!row || row.claimed_at || Date.parse(row.expires_at) < now) return null;
    const claimed = this.db
      .prepare("UPDATE enrollments SET claimed_at = ? WHERE code_hash = ? AND claimed_at IS NULL")
      .run(new Date(now).toISOString(), row.code_hash);
    if (claimed.changes !== 1) return null;
    const token = "hdr_" + randomBytes(32).toString("base64url");
    const device_id = "dev_" + randomBytes(8).toString("hex");
    const created_at = new Date(now).toISOString();
    this.db
      .prepare("INSERT INTO devices (device_id, name, token_hash, scopes, created_at) VALUES (?, ?, ?, ?, ?)")
      .run(device_id, name.slice(0, 64) || "Rabbit R1", sha(token), row.scopes, created_at);
    return { device: this.get(device_id)!, token };
  }

  authenticate(bearer: string | undefined): Device | null {
    if (!bearer?.startsWith("hdr_")) return null;
    const h = sha(bearer);
    const row = this.db.prepare("SELECT device_id, token_hash FROM devices WHERE token_hash = ? AND revoked_at IS NULL").get(h) as
      | { device_id: string; token_hash: string }
      | undefined;
    if (!row || !timingSafeEqual(Buffer.from(row.token_hash), Buffer.from(h))) return null;
    this.db.prepare("UPDATE devices SET last_seen_at = ? WHERE device_id = ?").run(new Date().toISOString(), row.device_id);
    return this.get(row.device_id);
  }

  get(id: string): Device | null {
    const r = this.db.prepare("SELECT * FROM devices WHERE device_id = ?").get(id) as Record<string, string | null> | undefined;
    if (!r) return null;
    return {
      device_id: r.device_id!,
      name: r.name!,
      scopes: r.scopes!.split(",").filter(Boolean) as Scope[],
      machines: r.machines === "*" ? "*" : r.machines!.split(","),
      created_at: r.created_at!,
      last_seen_at: r.last_seen_at,
      revoked_at: r.revoked_at,
    };
  }

  list(): Device[] {
    return (this.db.prepare("SELECT device_id FROM devices ORDER BY created_at").all() as { device_id: string }[]).map((r) => this.get(r.device_id)!);
  }

  /** Lost-device path: the token stops working immediately for reads and mutations. */
  revoke(id: string): boolean {
    return this.db.prepare("UPDATE devices SET revoked_at = ? WHERE device_id = ? AND revoked_at IS NULL").run(new Date().toISOString(), id).changes === 1;
  }
}

export function canAccessMachine(d: Device, machineId: string) {
  return d.machines === "*" || d.machines.includes(machineId);
}
