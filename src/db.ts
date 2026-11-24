import { Database } from "bun:sqlite";

export interface Reading {
  ts: number; // epoch ms, minute-rounded
  mgdl: number;
  source: string; // "graph" | "logbook" | "latest" | "csv"
  trend: number | null;
}

export interface Meal {
  id: number;
  ts: number;
  title: string;
  kind: string;
  carbs: number | null;
  note: string;
  photo: string | null;
  created_at: number;
}

export type MealInput = Pick<Meal, "ts" | "title" | "kind" | "carbs" | "note" | "photo">;

export function openDb(path: string): Database {
  const db = new Database(path, { create: true });
  db.exec("PRAGMA journal_mode = WAL;");
  db.exec(`
    CREATE TABLE IF NOT EXISTS readings (
      ts INTEGER PRIMARY KEY,
      mgdl REAL NOT NULL,
      source TEXT NOT NULL,
      trend INTEGER
    );
    CREATE TABLE IF NOT EXISTS meals (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      ts INTEGER NOT NULL,
      title TEXT NOT NULL,
      kind TEXT NOT NULL DEFAULT '',
      carbs REAL,
      note TEXT NOT NULL DEFAULT '',
      photo TEXT,
      created_at INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS meals_ts ON meals(ts);
    CREATE TABLE IF NOT EXISTS meta (
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    );
  `);
  return db;
}

export class Store {
  constructor(readonly db: Database) {}

  /** API readings: later API data for the same minute replaces earlier data. */
  upsertReadings(rows: Reading[]): number {
    const stmt = this.db.prepare(
      `INSERT INTO readings (ts, mgdl, source, trend) VALUES (?, ?, ?, ?)
       ON CONFLICT(ts) DO UPDATE SET
         mgdl = excluded.mgdl,
         trend = COALESCE(excluded.trend, readings.trend),
         source = excluded.source
       WHERE readings.source != 'graph' OR excluded.source = 'graph'`,
    );
    let n = 0;
    this.db.transaction(() => {
      for (const r of rows) n += stmt.run(r.ts, r.mgdl, r.source, r.trend).changes;
    })();
    return n;
  }

  /**
   * CSV readings: skip a row when any non-CSV reading lies within
   * `windowMin` minutes, so API and CSV points of the same curve don't interleave.
   */
  insertCsvReadings(rows: Reading[], windowMin = 7): { inserted: number; skipped: number } {
    const near = this.db.prepare(
      "SELECT 1 FROM readings WHERE ts BETWEEN ? AND ? AND source != 'csv' LIMIT 1",
    );
    const ins = this.db.prepare(
      "INSERT OR IGNORE INTO readings (ts, mgdl, source, trend) VALUES (?, ?, 'csv', NULL)",
    );
    const w = windowMin * 60_000;
    let inserted = 0;
    let skipped = 0;
    this.db.transaction(() => {
      for (const r of rows) {
        if (near.get(r.ts - w, r.ts + w)) {
          skipped++;
          continue;
        }
        const c = ins.run(r.ts, r.mgdl).changes;
        if (c) inserted++;
        else skipped++;
      }
    })();
    return { inserted, skipped };
  }

  readings(from: number, to: number): Reading[] {
    return this.db
      .query("SELECT ts, mgdl, source, trend FROM readings WHERE ts BETWEEN ? AND ? ORDER BY ts")
      .all(from, to) as Reading[];
  }

  readingStats(): { count: number; first: number | null; last: number | null } {
    return this.db
      .query("SELECT COUNT(*) AS count, MIN(ts) AS first, MAX(ts) AS last FROM readings")
      .get() as { count: number; first: number | null; last: number | null };
  }

  latestReading(): Reading | null {
    return (this.db.query("SELECT * FROM readings ORDER BY ts DESC LIMIT 1").get() as Reading) ?? null;
  }

  meals(from: number, to: number): Meal[] {
    return this.db.query("SELECT * FROM meals WHERE ts BETWEEN ? AND ? ORDER BY ts").all(from, to) as Meal[];
  }

  meal(id: number): Meal | null {
    return (this.db.query("SELECT * FROM meals WHERE id = ?").get(id) as Meal) ?? null;
  }

  addMeal(m: MealInput): Meal {
    const r = this.db
      .query(
        `INSERT INTO meals (ts, title, kind, carbs, note, photo, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING *`,
      )
      .get(m.ts, m.title, m.kind, m.carbs, m.note, m.photo, Date.now()) as Meal;
    return r;
  }

  updateMeal(id: number, m: Partial<MealInput>): Meal | null {
    const cur = this.meal(id);
    if (!cur) return null;
    const next = { ...cur, ...m };
    this.db
      .query("UPDATE meals SET ts=?, title=?, kind=?, carbs=?, note=?, photo=? WHERE id=?")
      .run(next.ts, next.title, next.kind, next.carbs, next.note, next.photo, id);
    return this.meal(id);
  }

  deleteMeal(id: number): Meal | null {
    const cur = this.meal(id);
    if (cur) this.db.query("DELETE FROM meals WHERE id = ?").run(id);
    return cur;
  }

  getMeta(key: string): string | null {
    const r = this.db.query("SELECT value FROM meta WHERE key = ?").get(key) as { value: string } | null;
    return r?.value ?? null;
  }

  setMeta(key: string, value: string): void {
    this.db
      .query("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
      .run(key, value);
  }
}
