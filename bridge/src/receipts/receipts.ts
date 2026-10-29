import { createHash, randomBytes } from "node:crypto";
import type { ActionReceipt, ReceiptStatus } from "../protocol.ts";
import type { Store } from "../store.ts";

// Every mutation is keyed by (device, Idempotency-Key). A retry with the same key and body
// returns the original receipt instead of dispatching twice; the same key with a different
// body is a conflict. Status moves pending → accepted|rejected|unknown exactly once.

export class IdempotencyConflict extends Error {
  readonly code = "idempotency_conflict";
}

export class Receipts {
  readonly db: Store;
  constructor(db: Store) {
    this.db = db;
  }

  /** Returns `{receipt, fresh}`; only a fresh receipt may be executed. */
  begin(deviceId: string, key: string, kind: string, scope: Record<string, unknown>, body: unknown): { receipt: ActionReceipt; fresh: boolean } {
    const requestHash = createHash("sha256").update(kind).update(JSON.stringify(body ?? null)).digest("hex");
    const existing = this.db.prepare("SELECT request_hash, body FROM receipts WHERE device_id = ? AND idempotency_key = ?").get(deviceId, key) as
      | { request_hash: string; body: string }
      | undefined;
    if (existing) {
      if (existing.request_hash !== requestHash) throw new IdempotencyConflict("Idempotency-Key reused with a different request");
      return { receipt: JSON.parse(existing.body), fresh: false };
    }
    const now = new Date().toISOString();
    const receipt: ActionReceipt = {
      action_id: "act_" + randomBytes(9).toString("base64url"),
      idempotency_key: key,
      device_id: deviceId,
      kind,
      scope,
      status: "pending",
      detail: null,
      created_at: now,
      updated_at: now,
      result: null,
    };
    this.db
      .prepare("INSERT INTO receipts (action_id, device_id, idempotency_key, request_hash, body) VALUES (?, ?, ?, ?, ?)")
      .run(receipt.action_id, deviceId, key, requestHash, JSON.stringify(receipt));
    return { receipt, fresh: true };
  }

  settle(actionId: string, status: Exclude<ReceiptStatus, "pending">, detail: string | null, result: Record<string, unknown> | null = null): ActionReceipt {
    const r = this.get(actionId);
    if (!r) throw new Error(`unknown action ${actionId}`);
    if (r.status !== "pending") return r;
    const next: ActionReceipt = { ...r, status, detail, result, updated_at: new Date().toISOString() };
    this.db.prepare("UPDATE receipts SET body = ? WHERE action_id = ?").run(JSON.stringify(next), actionId);
    return next;
  }

  get(actionId: string): ActionReceipt | null {
    const row = this.db.prepare("SELECT body FROM receipts WHERE action_id = ?").get(actionId) as { body: string } | undefined;
    return row ? JSON.parse(row.body) : null;
  }

  byKey(deviceId: string, key: string): ActionReceipt | null {
    const row = this.db.prepare("SELECT body FROM receipts WHERE device_id = ? AND idempotency_key = ?").get(deviceId, key) as { body: string } | undefined;
    return row ? JSON.parse(row.body) : null;
  }
}
