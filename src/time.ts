// Timestamp parsing for LibreLinkUp API values and LibreView CSV exports.

const MIN = 60_000;

/**
 * Parse a LibreLinkUp timestamp.
 * `FactoryTimestamp` is seen as UTC in the form "11/24/2026 10:05:00 AM".
 * Also accepts ISO strings and the 24-hour form "11/24/2026 22:05:00".
 * Values without a zone are read as UTC. Returns epoch ms, or null.
 */
export function parseLluTimestamp(raw: unknown): number | null {
  if (typeof raw === "number" && Number.isFinite(raw)) {
    return raw < 1e12 ? raw * 1000 : raw; // seconds or ms
  }
  if (typeof raw !== "string") return null;
  const s = raw.trim();
  if (!s) return null;

  const us = s.match(
    /^(\d{1,2})\/(\d{1,2})\/(\d{4})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?\s*([AaPp][Mm])?$/,
  );
  if (us) {
    const [, mo, d, y, hh, mi, ss, ampm] = us;
    let h = Number(hh);
    if (ampm) {
      const pm = ampm.toUpperCase() === "PM";
      if (h === 12) h = pm ? 12 : 0;
      else if (pm) h += 12;
    }
    const t = Date.UTC(Number(y), Number(mo) - 1, Number(d), h, Number(mi), Number(ss ?? 0));
    return Number.isFinite(t) ? t : null;
  }

  const iso = s.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$/);
  if (iso) {
    const zone = iso[7];
    const t = Date.parse(zone ? s.replace(" ", "T") : `${s.replace(" ", "T")}Z`);
    return Number.isFinite(t) ? t : null;
  }
  return null;
}

/** Round to the nearest minute, used as the reading key. */
export function toMinute(ms: number): number {
  return Math.round(ms / MIN) * MIN;
}

export type DateOrder = "MDY" | "DMY" | "YMD";

export interface LocalParts {
  a: number; // first number in the date
  b: number;
  c: number;
  h: number;
  mi: number;
  ss: number;
  ymd: boolean; // date starts with a 4-digit year
}

/**
 * Split a local CSV timestamp into parts without deciding the date order.
 * Handles "11-24-2026 10:05", "24-11-2026 22:05", "2026/11/24 10:05",
 * "11/24/2026 10:05 AM", "2026-11-24 上午 10:05" and similar.
 */
export function splitLocalTimestamp(raw: string): LocalParts | null {
  let s = raw.trim();
  if (!s) return null;
  let meridiem: "AM" | "PM" | null = null;
  if (/上午|AM|am|a\.m\./.test(s)) meridiem = "AM";
  if (/下午|PM|pm|p\.m\./.test(s)) meridiem = "PM";
  s = s.replace(/上午|下午|[AaPp]\.?[Mm]\.?/g, " ").replace(/\s+/g, " ").trim();

  const m = s.match(/^(\d{1,4})[-/.](\d{1,2})[-/.](\d{1,4})[ T](\d{1,2}):(\d{2})(?::(\d{2}))?$/);
  if (!m) return null;
  let h = Number(m[4]);
  if (meridiem) {
    if (h === 12) h = meridiem === "PM" ? 12 : 0;
    else if (meridiem === "PM") h += 12;
  }
  return {
    a: Number(m[1]),
    b: Number(m[2]),
    c: Number(m[3]),
    h,
    mi: Number(m[5]),
    ss: Number(m[6] ?? 0),
    ymd: m[1]!.length === 4,
  };
}

/** Pick the date order from many samples: any first field > 12 means DMY. */
export function detectDateOrder(samples: LocalParts[]): DateOrder {
  if (samples.length && samples.every((p) => p.ymd)) return "YMD";
  let firstOver12 = false;
  let secondOver12 = false;
  for (const p of samples) {
    if (p.ymd) continue;
    if (p.a > 12) firstOver12 = true;
    if (p.b > 12) secondOver12 = true;
  }
  if (firstOver12 && !secondOver12) return "DMY";
  return "MDY"; // LibreView's US default; also the choice when ambiguous
}

/**
 * Build epoch ms from local parts. `tzOffsetMin` is minutes east of UTC
 * (Taiwan = 480). When null, the server's local time zone is used.
 */
export function partsToEpoch(p: LocalParts, order: DateOrder, tzOffsetMin: number | null): number | null {
  let y: number, mo: number, d: number;
  if (p.ymd || order === "YMD") [y, mo, d] = [p.a, p.b, p.c];
  else if (order === "DMY") [d, mo, y] = [p.a, p.b, p.c];
  else [mo, d, y] = [p.a, p.b, p.c];
  if (y < 100) y += 2000;
  if (mo < 1 || mo > 12 || d < 1 || d > 31 || p.h > 23 || p.mi > 59) return null;
  if (tzOffsetMin === null) {
    const t = new Date(y, mo - 1, d, p.h, p.mi, p.ss).getTime();
    return Number.isFinite(t) ? t : null;
  }
  const t = Date.UTC(y, mo - 1, d, p.h, p.mi, p.ss) - tzOffsetMin * MIN;
  return Number.isFinite(t) ? t : null;
}
