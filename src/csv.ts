// Parser for the LibreView "Download glucose data" CSV export.
// Row 1 is metadata ("Glucose Data,Generated on,..."), row 2 is the header.
// Columns are found by header name, with the usual LibreView positions as fallback.

import type { Reading } from "./db.ts";
import { detectDateOrder, partsToEpoch, splitLocalTimestamp, toMinute, type DateOrder } from "./time.ts";

const MMOL_TO_MGDL = 18.016;

/** Minimal RFC 4180 parser (quotes, escaped quotes, CRLF). */
export function parseCsvRows(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = "";
  let quoted = false;
  const s = text.replace(/^﻿/, "");
  for (let i = 0; i < s.length; i++) {
    const ch = s[i]!;
    if (quoted) {
      if (ch === '"') {
        if (s[i + 1] === '"') {
          field += '"';
          i++;
        } else quoted = false;
      } else field += ch;
      continue;
    }
    if (ch === '"') quoted = true;
    else if (ch === ",") {
      row.push(field);
      field = "";
    } else if (ch === "\n" || ch === "\r") {
      if (ch === "\r" && s[i + 1] === "\n") i++;
      row.push(field);
      rows.push(row);
      row = [];
      field = "";
    } else field += ch;
  }
  if (field !== "" || row.length) {
    row.push(field);
    rows.push(row);
  }
  return rows.filter((r) => r.some((f) => f.trim() !== ""));
}

interface Columns {
  ts: number;
  type: number;
  historic: number;
  scan: number;
  historicMmol: boolean;
  scanMmol: boolean;
}

const norm = (h: string) => h.toLowerCase().replace(/\s+/g, " ").trim();

function findHeader(rows: string[][]): number {
  for (let i = 0; i < Math.min(rows.length, 5); i++) {
    const r = rows[i]!.map(norm);
    if (r.some((h) => h.includes("timestamp") || h.includes("時間")) && r.some((h) => h.includes("glucose") || h.includes("血糖")))
      return i;
  }
  return -1;
}

function mapColumns(header: string[]): Columns {
  const h = header.map(norm);
  const idx = (pred: (s: string) => boolean, fallback: number) => {
    const i = h.findIndex(pred);
    return i >= 0 ? i : fallback;
  };
  const ts = idx((s) => s.includes("timestamp") || s.includes("時間"), 2);
  const type = idx((s) => s.includes("record type") || s.includes("記錄類型") || s.includes("紀錄類型"), 3);
  const historic = idx((s) => (s.includes("historic") || s.includes("歷史")) && (s.includes("glucose") || s.includes("血糖")), 4);
  const scan = idx((s) => (s.includes("scan") || s.includes("掃描")) && (s.includes("glucose") || s.includes("血糖")), 5);
  return {
    ts,
    type,
    historic,
    scan,
    historicMmol: (h[historic] ?? "").includes("mmol"),
    scanMmol: (h[scan] ?? "").includes("mmol"),
  };
}

export interface CsvResult {
  readings: Reading[];
  dateOrder: DateOrder;
  unit: "mg/dL" | "mmol/L";
  rowsTotal: number;
  rowsSkipped: number;
}

/**
 * Parse a LibreView CSV. Timestamps in the export are local device time;
 * `tzOffsetMin` (minutes east of UTC, Taiwan = 480) sets the zone, or null
 * to use the server's zone.
 */
export function parseLibreViewCsv(text: string, tzOffsetMin: number | null = null): CsvResult {
  const rows = parseCsvRows(text);
  const hi = findHeader(rows);
  const header = hi >= 0 ? rows[hi]! : [];
  const cols = mapColumns(header);
  const body = rows.slice(hi >= 0 ? hi + 1 : 0);

  const parsed = body.map((r) => ({ r, p: splitLocalTimestamp(r[cols.ts] ?? "") }));
  const order = detectDateOrder(parsed.flatMap((x) => (x.p ? [x.p] : [])));

  // Unit when the header has none: mmol/L values are all below ~35.
  const anyUnitInHeader = header.some((h) => /mg\/dl|mmol/i.test(h));
  let guessMmol = false;
  if (!anyUnitInHeader) {
    const vals = body.flatMap((r) => [Number(r[cols.historic]), Number(r[cols.scan])]).filter((v) => Number.isFinite(v) && v > 0);
    guessMmol = vals.length > 0 && Math.max(...vals) < 35;
  }
  const histMmol = anyUnitInHeader ? cols.historicMmol : guessMmol;
  const scanMmol = anyUnitInHeader ? cols.scanMmol : guessMmol;

  const out = new Map<number, Reading>();
  let skipped = 0;
  for (const { r, p } of parsed) {
    if (!p) {
      skipped++;
      continue;
    }
    const type = (r[cols.type] ?? "").trim();
    let raw: number;
    let mmol: boolean;
    if (type === "0") {
      raw = Number((r[cols.historic] ?? "").replace(",", "."));
      mmol = histMmol;
    } else if (type === "1") {
      raw = Number((r[cols.scan] ?? "").replace(",", "."));
      mmol = scanMmol;
    } else {
      skipped++; // notes, insulin, food, etc.
      continue;
    }
    const ts = partsToEpoch(p, order, tzOffsetMin);
    if (ts === null || !Number.isFinite(raw) || raw <= 0) {
      skipped++;
      continue;
    }
    const mgdl = Math.round((mmol ? raw * MMOL_TO_MGDL : raw) * 10) / 10;
    const key = toMinute(ts);
    // Historic (type 0) wins over a scan at the same minute.
    if (!out.has(key) || type === "0") out.set(key, { ts: key, mgdl, source: "csv", trend: null });
  }
  return {
    readings: [...out.values()].sort((a, b) => a.ts - b.ts),
    dateOrder: order,
    unit: histMmol ? "mmol/L" : "mg/dL",
    rowsTotal: body.length,
    rowsSkipped: skipped,
  };
}
