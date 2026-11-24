import { describe, expect, test } from "bun:test";
import { analyzeMeal } from "../src/analysis.ts";
import { LluClient } from "../src/llu.ts";
import { createMockFetch, mockHostFor } from "../src/mock.ts";
import { Poller, checkClockSkew } from "../src/poller.ts";
import { openDb, Store } from "../src/db.ts";

const MIN = 60_000;
const HOUR = 60 * MIN;

describe("login step with a limited authTicket", () => {
  test("is reported as a step, not accepted as a login", async () => {
    const c = new LluClient({ email: "a", password: "needs-tou", fetch: createMockFetch(), hostFor: mockHostFor });
    const e = await c.connections().catch((x) => x);
    expect(e.kind).toBe("step");
    expect(e.message).toContain("tou");
    expect(e.fatal).toBe(true);
  });
});

describe("prior meal", () => {
  test("a meal in the 3 hours before lowers confidence", () => {
    const t0 = Date.UTC(2026, 10, 24, 5, 0);
    const pts = Array.from({ length: 20 }, (_, i) => ({ ts: t0 - 30 * MIN + i * 15 * MIN, mgdl: 140 }));
    const r = analyzeMeal(t0, pts, [t0 - 60 * MIN], { now: t0 + 24 * HOUR });
    expect(r.flags).toContain("prior_meal");
    expect(r.confidence).toBe("low");
    // 4 hours before is outside the window
    expect(analyzeMeal(t0, pts, [t0 - 4 * HOUR], { now: t0 + 24 * HOUR }).flags).not.toContain("prior_meal");
  });
});

describe("clock skew check", () => {
  const now = Date.UTC(2026, 10, 24, 10, 0);
  test("fresh data is fine; stale but not whole-hour is fine", () => {
    expect(checkClockSkew(now - 3 * MIN, now)).toBeNull();
    expect(checkClockSkew(now - 95 * MIN, now)).toBeNull();
    expect(checkClockSkew(null, now)).toBeNull();
  });
  test("future or whole-hour offsets warn", () => {
    expect(checkClockSkew(now + 8 * HOUR, now)).toContain("比現在晚");
    expect(checkClockSkew(now - 8 * HOUR - 2 * MIN, now)).toContain("8 小時");
  });
  test("poller surfaces the warning when the API clock is 8 hours off", async () => {
    const c = new LluClient({
      email: "a",
      password: "b",
      fetch: createMockFetch({ now: () => Date.now() - 8 * HOUR }),
      hostFor: mockHostFor,
    });
    const p = new Poller(c, new Store(openDb(":memory:")), { intervalMin: 5, log: () => {} });
    await p.pollNow();
    p.stop();
    expect(p.status.state).toBe("ok");
    expect(p.status.timeWarning).toContain("8 小時");
  });
});
