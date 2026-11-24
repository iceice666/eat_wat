// Client for the unofficial LibreLinkUp (follower app) API.
// Abbott does not publish this API. Behaviour is based on community clients
// (pylibrelinkup, nightscout-librelink-up) and may change without notice.

import { createHash } from "node:crypto";
import { parseLluTimestamp, toMinute } from "./time.ts";
import type { Reading } from "./db.ts";

export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

export type LluErrorKind =
  | "config" // credentials missing
  | "auth" // login rejected; retrying will not help
  | "step" // the account must accept terms / privacy in the app first
  | "no_connections" // nobody shares data with this follower account
  | "rate_limited"
  | "http"
  | "network";

export class LluError extends Error {
  constructor(
    readonly kind: LluErrorKind,
    message: string,
    readonly raw?: unknown,
  ) {
    super(message);
  }
  /** Fatal errors need the user to act; the poller stops retrying on these. */
  get fatal(): boolean {
    return this.kind === "config" || this.kind === "auth" || this.kind === "step" || this.kind === "no_connections";
  }
}

export interface Connection {
  patientId: string;
  firstName?: string;
  lastName?: string;
  [k: string]: unknown;
}

export interface LluClientOptions {
  email: string;
  password: string;
  region?: string;
  version?: string;
  fetch?: FetchLike;
  /** Overrides host building, used by the mock. Receives the region ("" = global). */
  hostFor?: (region: string) => string;
}

const GLOBAL_HOST = "https://api.libreview.io";
const defaultHostFor = (region: string) => (region ? `https://api-${region}.libreview.io` : GLOBAL_HOST);

export function accountIdFor(userId: string): string {
  return createHash("sha256").update(userId).digest("hex");
}

export class LluClient {
  private token: string | null = null;
  private tokenExpires = 0; // epoch ms
  private accountId: string | null = null;
  private region: string;
  private readonly fetchImpl: FetchLike;
  private readonly hostFor: (region: string) => string;
  loginCount = 0;

  constructor(private readonly opts: LluClientOptions) {
    this.region = opts.region ?? "";
    this.fetchImpl = opts.fetch ?? ((i, init) => fetch(i, init));
    this.hostFor = opts.hostFor ?? defaultHostFor;
  }

  get currentRegion(): string {
    return this.region;
  }

  private baseHeaders(): Record<string, string> {
    return {
      "accept-encoding": "gzip",
      "cache-control": "no-cache",
      "content-type": "application/json",
      product: "llu.android",
      version: this.opts.version || "4.16.0",
    };
  }

  private async send(path: string, init: RequestInit): Promise<{ status: number; body: any }> {
    let res: Response;
    try {
      res = await this.fetchImpl(this.hostFor(this.region) + path, init);
    } catch (e) {
      throw new LluError("network", `無法連線到 LibreView：${(e as Error).message}`);
    }
    const text = await res.text();
    let body: any = null;
    try {
      body = text ? JSON.parse(text) : null;
    } catch {
      body = { nonJson: text.slice(0, 300) };
    }
    if (res.status === 429) throw new LluError("rate_limited", "LibreView 回應 429（請求太頻繁），稍後重試", body);
    return { status: res.status, body };
  }

  async login(): Promise<void> {
    if (!this.opts.email || !this.opts.password) {
      throw new LluError("config", "尚未設定 LLU_EMAIL / LLU_PASSWORD（.env）");
    }
    // At most one region redirect.
    for (let attempt = 0; attempt < 2; attempt++) {
      this.loginCount++;
      const { status, body } = await this.send("/llu/auth/login", {
        method: "POST",
        headers: this.baseHeaders(),
        body: JSON.stringify({ email: this.opts.email, password: this.opts.password }),
      });
      const data = body?.data;
      if (data?.redirect && typeof data.region === "string" && data.region !== this.region) {
        this.region = data.region;
        continue;
      }
      // A terms/privacy step can come with a limited authTicket; never treat it as a login.
      if (data?.step) {
        const t = data.step.type ?? JSON.stringify(data.step).slice(0, 80);
        throw new LluError(
          "step",
          `LibreLinkUp 帳號需要先在 App 內完成步驟（step=${t}，status=${body?.status}）。請打開 LibreLinkUp App 登入一次並接受條款。`,
          body,
        );
      }
      if (body?.status === 0 && data?.authTicket?.token && data?.user?.id) {
        this.token = data.authTicket.token;
        const exp = Number(data.authTicket.expires);
        // `expires` is seen in unix seconds; fall back to 1 hour.
        this.tokenExpires = Number.isFinite(exp) && exp > 0 ? (exp < 1e12 ? exp * 1000 : exp) : Date.now() + 3600_000;
        this.accountId = accountIdFor(String(data.user.id));
        return;
      }
      if (status >= 500) throw new LluError("http", `登入失敗：HTTP ${status}`, body);
      const msg = body?.error?.message ?? body?.message ?? "";
      throw new LluError(
        "auth",
        `登入被拒絕（HTTP ${status}，status=${body?.status ?? "?"}${msg ? `，${msg}` : ""}）。請檢查 .env 的帳號密碼。`,
        body,
      );
    }
    throw new LluError("http", "登入時區域轉址超過一次", null);
  }

  private tokenValid(): boolean {
    return !!this.token && Date.now() < this.tokenExpires - 60_000;
  }

  async get(path: string): Promise<any> {
    if (!this.tokenValid()) await this.login();
    for (let attempt = 0; attempt < 2; attempt++) {
      const { status, body } = await this.send(path, {
        method: "GET",
        headers: {
          ...this.baseHeaders(),
          authorization: `Bearer ${this.token}`,
          "account-id": this.accountId!,
        },
      });
      if (status === 401 && attempt === 0) {
        this.token = null;
        await this.login();
        continue;
      }
      if (status >= 400) {
        const msg = body?.message ?? body?.error?.message ?? "";
        throw new LluError("http", `GET ${path} 失敗：HTTP ${status}${msg ? `（${msg}）` : ""}`, body);
      }
      if (body?.status !== undefined && body.status !== 0) {
        throw new LluError("http", `GET ${path} 回應 status=${body.status}`, body);
      }
      return body?.data;
    }
    throw new LluError("auth", "重新登入後仍回應 401", null);
  }

  async connections(): Promise<Connection[]> {
    const data = await this.get("/llu/connections");
    return Array.isArray(data) ? data : [];
  }

  async graph(patientId: string): Promise<any> {
    return this.get(`/llu/connections/${encodeURIComponent(patientId)}/graph`);
  }

  async logbook(patientId: string): Promise<any[]> {
    const data = await this.get(`/llu/connections/${encodeURIComponent(patientId)}/logbook`);
    return Array.isArray(data) ? data : [];
  }
}

/** Convert one LLU glucose item to a Reading. Returns null when unusable. */
export function itemToReading(item: any, source: string): Reading | null {
  if (!item || typeof item !== "object") return null;
  const ts = parseLluTimestamp(item.FactoryTimestamp) ?? null;
  let mgdl = Number(item.ValueInMgPerDl);
  if (!Number.isFinite(mgdl) || mgdl <= 0) {
    const v = Number(item.Value);
    // GlucoseUnits 0 = mmol/L in community docs; a value under 35 is mmol/L anyway.
    mgdl = Number.isFinite(v) && v > 0 ? (v < 35 ? v * 18.016 : v) : NaN;
  }
  if (ts === null || !Number.isFinite(mgdl)) return null;
  const trend = Number.isFinite(Number(item.TrendArrow)) ? Number(item.TrendArrow) : null;
  return { ts: toMinute(ts), mgdl: Math.round(mgdl * 10) / 10, source, trend };
}
