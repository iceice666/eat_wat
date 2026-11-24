// Post-meal glucose response metrics.

export interface Point {
  ts: number;
  mgdl: number;
}

export type Flag =
  | "no_baseline" // no reading in the 30 min before the meal
  | "baseline_after" // baseline taken from a reading just after the meal
  | "gap" // a gap longer than maxGapMin inside the window
  | "low_coverage" // window not well covered by readings
  | "overlap" // another meal starts inside the window
  | "prior_meal" // another meal in the 3 hours before; baseline may sit on its curve
  | "incomplete" // the window has not ended yet / data stops early
  | "no_data";

export interface MealResponse {
  baseline: number | null;
  baselineTs: number | null;
  peak: number | null;
  peakTs: number | null;
  minutesToPeak: number | null;
  delta: number | null; // peak − baseline
  at2h: number | null; // interpolated value 120 min after the meal
  delta2h: number | null;
  iauc: number | null; // incremental AUC above baseline, mg/dL·h
  minutesToReturn: number | null; // first time after the peak within 10 mg/dL of baseline
  readingsInWindow: number;
  flags: Flag[];
  confidence: "high" | "low";
}

export interface AnalysisOptions {
  windowMin?: number; // default 180
  baselineMin?: number; // default 30
  maxGapMin?: number; // default 30 (Libre history can be 15 min apart)
  now?: number;
}

const MIN = 60_000;

function interpolate(points: Point[], t: number, maxGap: number): number | null {
  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1]!;
    const b = points[i]!;
    if (a.ts <= t && t <= b.ts) {
      if (b.ts - a.ts > maxGap) return null;
      if (b.ts === a.ts) return a.mgdl;
      return a.mgdl + ((b.mgdl - a.mgdl) * (t - a.ts)) / (b.ts - a.ts);
    }
  }
  return null;
}

/**
 * `points` must be sorted by ts. `otherMeals` are timestamps of other meals
 * (the meal itself may be included; it is ignored).
 */
export function analyzeMeal(mealTs: number, points: Point[], otherMeals: number[] = [], opts: AnalysisOptions = {}): MealResponse {
  const windowMs = (opts.windowMin ?? 180) * MIN;
  const baselineMs = (opts.baselineMin ?? 30) * MIN;
  const maxGap = (opts.maxGapMin ?? 30) * MIN;
  const now = opts.now ?? Date.now();
  const end = mealTs + windowMs;
  const flags: Flag[] = [];

  const empty: MealResponse = {
    baseline: null,
    baselineTs: null,
    peak: null,
    peakTs: null,
    minutesToPeak: null,
    delta: null,
    at2h: null,
    delta2h: null,
    iauc: null,
    minutesToReturn: null,
    readingsInWindow: 0,
    flags,
    confidence: "low",
  };

  // Baseline: latest reading in [meal − 30 min, meal]; else first in (meal, meal + 10 min].
  let base: Point | undefined;
  for (const p of points) {
    if (p.ts >= mealTs - baselineMs && p.ts <= mealTs) base = p;
    if (p.ts > mealTs) break;
  }
  if (!base) {
    base = points.find((p) => p.ts > mealTs && p.ts <= mealTs + 10 * MIN);
    if (base) flags.push("baseline_after");
    else flags.push("no_baseline");
  }

  const win = points.filter((p) => p.ts > mealTs && p.ts <= end);
  if (otherMeals.some((t) => t > mealTs && t < end)) flags.push("overlap");
  if (otherMeals.some((t) => t < mealTs && t >= mealTs - windowMs)) flags.push("prior_meal");

  if (!win.length) {
    flags.push("no_data");
    if (end > now) flags.push("incomplete");
    return { ...empty, baseline: base?.mgdl ?? null, baselineTs: base?.ts ?? null };
  }

  // Gaps and coverage.
  const series = base ? [base, ...win.filter((p) => p.ts !== base!.ts)] : win;
  let maxSeen = 0;
  for (let i = 1; i < series.length; i++) maxSeen = Math.max(maxSeen, series[i]!.ts - series[i - 1]!.ts);
  const lastTs = win[win.length - 1]!.ts;
  if (end > now || end - lastTs > maxGap) flags.push("incomplete");
  if (maxSeen > maxGap) flags.push("gap");
  const firstTs = win[0]!.ts;
  const covered = Math.min(lastTs, end) - Math.max(base?.ts ?? firstTs, mealTs);
  if (covered < windowMs * 0.7 && !flags.includes("incomplete")) flags.push("low_coverage");

  let peak = win[0]!;
  for (const p of win) if (p.mgdl > peak.mgdl) peak = p;

  const baseline = base?.mgdl ?? null;
  const at2h = interpolate(series, mealTs + 120 * MIN, maxGap);

  // Incremental AUC (trapezoids, positive part only), skipping gap segments.
  let iauc: number | null = null;
  if (baseline !== null) {
    let area = 0; // mg/dL·ms
    for (let i = 1; i < series.length; i++) {
      const a = series[i - 1]!;
      const b = series[i]!;
      const dt = b.ts - a.ts;
      if (dt <= 0 || dt > maxGap) continue;
      const ya = a.mgdl - baseline;
      const yb = b.mgdl - baseline;
      if (ya >= 0 && yb >= 0) area += ((ya + yb) / 2) * dt;
      else if (ya > 0 || yb > 0) {
        // crossing: only the positive triangle
        const pos = Math.max(ya, yb);
        const frac = pos / (Math.abs(ya) + Math.abs(yb));
        area += (pos / 2) * dt * frac;
      }
    }
    iauc = Math.round((area / (60 * MIN)) * 10) / 10;
  }

  let minutesToReturn: number | null = null;
  if (baseline !== null) {
    const back = win.find((p) => p.ts > peak.ts && p.mgdl <= baseline + 10);
    if (back) minutesToReturn = Math.round((back.ts - mealTs) / MIN);
  }

  const lowFlags: Flag[] = ["no_baseline", "gap", "low_coverage", "overlap", "prior_meal", "incomplete", "no_data"];
  return {
    baseline,
    baselineTs: base?.ts ?? null,
    peak: peak.mgdl,
    peakTs: peak.ts,
    minutesToPeak: Math.round((peak.ts - mealTs) / MIN),
    delta: baseline !== null ? Math.round((peak.mgdl - baseline) * 10) / 10 : null,
    at2h: at2h !== null ? Math.round(at2h * 10) / 10 : null,
    delta2h: at2h !== null && baseline !== null ? Math.round((at2h - baseline) * 10) / 10 : null,
    iauc,
    minutesToReturn,
    readingsInWindow: win.length,
    flags,
    confidence: flags.some((f) => lowFlags.includes(f)) ? "low" : "high",
  };
}

/** Readings relative to the meal for overlay charts: minutes and Δ from baseline. */
export function relativeCurve(mealTs: number, points: Point[], baseline: number | null, windowMin = 180) {
  const end = mealTs + windowMin * MIN;
  return points
    .filter((p) => p.ts >= mealTs - 30 * MIN && p.ts <= end)
    .map((p) => ({
      min: Math.round((p.ts - mealTs) / MIN),
      mgdl: p.mgdl,
      delta: baseline !== null ? Math.round((p.mgdl - baseline) * 10) / 10 : null,
    }));
}
