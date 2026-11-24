// In-process fake LibreLinkUp API (LLU_MOCK=1). It does what the real service
// does as far as this app relies on it: a region redirect on login, required
// version / account-id headers, 12 hours of graph data, and a logbook.

import { accountIdFor, type FetchLike } from "./llu.ts";

const MIN = 60_000;
const USER_ID = "00000000-mock-user-0000-000000000000";
const PATIENT_ID = "11111111-mock-patient-0000-000000000000";
const TOKEN = "mock-token";

/** Deterministic glucose curve with meal bumps at 07:30, 12:30, 19:00 local time. */
export function mockGlucoseAt(t: number): number {
  const d = new Date(t);
  const minutes = d.getHours() * 60 + d.getMinutes();
  let g = 96 + 6 * Math.sin((t / MIN / 1440) * 2 * Math.PI * 3);
  const meals: [number, number][] = [
    [7 * 60 + 30, 55],
    [12 * 60 + 30, 80],
    [19 * 60, 65],
  ];
  for (const [at, height] of meals) {
    for (const shift of [0, -1440]) {
      const dt = minutes - at - shift;
      if (dt > 0 && dt < 240) g += height * (dt / 45) * Math.exp(1 - dt / 45);
    }
  }
  const noise = Math.sin(t / 997_000) * 3;
  return Math.round(g + noise);
}

function factoryTs(t: number): string {
  const d = new Date(t);
  let h = d.getUTCHours();
  const ampm = h >= 12 ? "PM" : "AM";
  h = h % 12 || 12;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getUTCMonth() + 1}/${d.getUTCDate()}/${d.getUTCFullYear()} ${h}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())} ${ampm}`;
}

function item(t: number) {
  const v = mockGlucoseAt(t);
  return {
    FactoryTimestamp: factoryTs(t),
    Timestamp: new Date(t).toLocaleString("en-US"),
    type: 0,
    ValueInMgPerDl: v,
    TrendArrow: 3,
    MeasurementColor: 1,
    GlucoseUnits: 1,
    Value: v,
    isHigh: v > 180,
    isLow: v < 70,
  };
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

export const MOCK_REGION = "mock";
export const mockHostFor = (region: string) => (region ? `https://api-${region}.mock.invalid` : "https://api.mock.invalid");

export function createMockFetch(opts: { now?: () => number; connections?: boolean } = {}): FetchLike {
  const now = opts.now ?? Date.now;
  return async (input, init) => {
    const url = new URL(input);
    const h = new Headers(init?.headers);
    const regional = url.host.startsWith(`api-${MOCK_REGION}.`);
    if (!h.get("version") || !h.get("product")) return json(403, { status: 920, error: { message: "RequiredHeaderMissing" } });

    if (url.pathname === "/llu/auth/login") {
      if (!regional) return json(200, { status: 0, data: { redirect: true, region: MOCK_REGION } });
      const body = JSON.parse(String(init?.body ?? "{}"));
      if (body.password === "wrong") return json(200, { status: 2, error: { message: "notAuthenticated" } });
      if (body.password === "needs-tou")
        return json(200, {
          status: 4,
          data: {
            step: { type: "tou", componentName: "AcceptDocument" },
            user: { id: USER_ID },
            authTicket: { token: "limited-token", expires: Math.floor(now() / 1000) + 3600, duration: 3600000 },
          },
        });
      return json(200, {
        status: 0,
        data: {
          user: { id: USER_ID, firstName: "Mock", lastName: "Follower" },
          authTicket: { token: TOKEN, expires: Math.floor(now() / 1000) + 3600, duration: 3600000 },
        },
      });
    }

    if (h.get("authorization") !== `Bearer ${TOKEN}`) return json(401, { message: "Unauthorized" });
    if (h.get("account-id") !== accountIdFor(USER_ID)) return json(400, { status: 920, error: { message: "RequiredHeaderMissing" } });

    const t = Math.floor(now() / MIN) * MIN;
    if (url.pathname === "/llu/connections") {
      if (opts.connections === false) return json(200, { status: 0, data: [] });
      return json(200, {
        status: 0,
        data: [{ patientId: PATIENT_ID, firstName: "測試", lastName: "使用者", glucoseMeasurement: item(t) }],
      });
    }
    if (url.pathname === `/llu/connections/${PATIENT_ID}/graph`) {
      const graphData = [];
      for (let x = t - 12 * 60 * MIN; x < t; x += 5 * MIN) graphData.push(item(Math.floor(x / (5 * MIN)) * 5 * MIN));
      return json(200, {
        status: 0,
        data: { connection: { patientId: PATIENT_ID, glucoseMeasurement: item(t) }, graphData },
      });
    }
    if (url.pathname === `/llu/connections/${PATIENT_ID}/logbook`) {
      return json(200, { status: 0, data: [item(t - 3 * 60 * MIN), item(t - 7 * 60 * MIN)] });
    }
    return json(404, { message: "not found" });
  };
}
