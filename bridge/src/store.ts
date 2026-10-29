import { DatabaseSync } from "node:sqlite";

// SQLite holds device registrations, the bounded event log, action receipts and open
// approvals. No raw transcripts, terminal output or source files are persisted.

export const EVENT_RETENTION = 5000;

export function openStore(file: string) {
  const db = new DatabaseSync(file);
  db.exec(`
    PRAGMA journal_mode = WAL;
    CREATE TABLE IF NOT EXISTS devices (
      device_id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      token_hash TEXT NOT NULL UNIQUE,
      scopes TEXT NOT NULL,
      machines TEXT NOT NULL DEFAULT '*',
      created_at TEXT NOT NULL,
      last_seen_at TEXT,
      revoked_at TEXT
    );
    CREATE TABLE IF NOT EXISTS enrollments (
      code_hash TEXT PRIMARY KEY,
      scopes TEXT NOT NULL,
      expires_at TEXT NOT NULL,
      claimed_at TEXT
    );
    CREATE TABLE IF NOT EXISTS events (
      seq INTEGER PRIMARY KEY AUTOINCREMENT,
      event_id TEXT NOT NULL UNIQUE,
      body TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS receipts (
      action_id TEXT PRIMARY KEY,
      device_id TEXT NOT NULL,
      idempotency_key TEXT NOT NULL,
      request_hash TEXT NOT NULL,
      body TEXT NOT NULL,
      UNIQUE (device_id, idempotency_key)
    );
    CREATE TABLE IF NOT EXISTS audit (
      seq INTEGER PRIMARY KEY AUTOINCREMENT,
      at TEXT NOT NULL,
      device_id TEXT,
      action TEXT NOT NULL,
      target TEXT,
      outcome TEXT NOT NULL
    );
  `);
  return db;
}

export type Store = ReturnType<typeof openStore>;
