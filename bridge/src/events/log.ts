import { randomBytes } from "node:crypto";
import type { BridgeEvent, EventType } from "../protocol.ts";
import { EVENT_RETENTION, type Store } from "../store.ts";

// Bounded append-only event feed. Cursors are opaque to clients (`c<seq>`); a cursor older than
// retention yields `reset_required` and the client reconciles from a fresh snapshot.

export type Listener = (e: BridgeEvent) => void;

export class EventLog {
  readonly db: Store;
  readonly listeners = new Set<Listener>();

  constructor(db: Store) {
    this.db = db;
  }

  append(type: EventType, fields: { machine_id?: string | null; session_id?: string | null; generation?: number | null; payload?: Record<string, unknown> }): BridgeEvent {
    const event_id = "evt_" + randomBytes(9).toString("base64url");
    const partial = {
      event_id,
      occurred_at: new Date().toISOString(),
      machine_id: fields.machine_id ?? null,
      session_id: fields.session_id ?? null,
      generation: fields.generation ?? null,
      type,
      payload: fields.payload ?? {},
    };
    const r = this.db.prepare("INSERT INTO events (event_id, body) VALUES (?, ?)").run(event_id, JSON.stringify(partial));
    const seq = Number(r.lastInsertRowid);
    if (seq % 100 === 0) this.db.prepare("DELETE FROM events WHERE seq <= ?").run(seq - EVENT_RETENTION);
    const event: BridgeEvent = { ...partial, cursor: `c${seq}` };
    for (const l of this.listeners) l(event);
    return event;
  }

  head(): string {
    const r = this.db.prepare("SELECT MAX(seq) AS s FROM events").get() as { s: number | null };
    return `c${r.s ?? 0}`;
  }

  /** Events strictly after `cursor`, or `reset_required` if the gap was trimmed. */
  after(cursor: string | null, limit = 500): { events: BridgeEvent[]; reset_required: boolean; cursor: string } {
    const seq = parseCursor(cursor);
    const min = this.db.prepare("SELECT MIN(seq) AS s FROM events").get() as { s: number | null };
    if (seq !== null && min.s !== null && seq < min.s - 1) return { events: [], reset_required: true, cursor: this.head() };
    const rows = this.db.prepare("SELECT seq, body FROM events WHERE seq > ? ORDER BY seq LIMIT ?").all(seq ?? 0, limit) as { seq: number; body: string }[];
    const events = rows.map((r) => ({ ...JSON.parse(r.body), cursor: `c${r.seq}` }) as BridgeEvent);
    return { events, reset_required: false, cursor: events.length ? events[events.length - 1].cursor : cursor ?? this.head() };
  }

  forSession(sessionId: string, cursor: string | null, limit = 50): BridgeEvent[] {
    const seq = parseCursor(cursor);
    const rows = this.db
      .prepare("SELECT seq, body FROM events WHERE json_extract(body, '$.session_id') = ? AND (? IS NULL OR seq < ?) ORDER BY seq DESC LIMIT ?")
      .all(sessionId, seq, seq, limit) as { seq: number; body: string }[];
    return rows.map((r) => ({ ...JSON.parse(r.body), cursor: `c${r.seq}` }));
  }

  recent(limit = 50, types?: EventType[]): BridgeEvent[] {
    const rows = this.db.prepare("SELECT seq, body FROM events ORDER BY seq DESC LIMIT ?").all(limit * 4) as { seq: number; body: string }[];
    const out: BridgeEvent[] = [];
    for (const r of rows) {
      const e = { ...JSON.parse(r.body), cursor: `c${r.seq}` } as BridgeEvent;
      if (!types || types.includes(e.type)) out.push(e);
      if (out.length >= limit) break;
    }
    return out;
  }

  subscribe(l: Listener) {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }
}

function parseCursor(c: string | null): number | null {
  if (!c) return null;
  const m = /^c(\d+)$/.exec(c);
  if (!m) throw Object.assign(new Error("malformed cursor"), { code: "bad_cursor" });
  return Number(m[1]);
}
