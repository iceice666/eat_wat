import { itemToReading, LluClient, LluError, type Connection } from "./llu.ts";
import type { Reading, Store } from "./db.ts";

export interface PollStatus {
  state: "idle" | "ok" | "error" | "stopped" | "disabled";
  lastAttempt: number | null;
  lastSuccess: number | null;
  lastError: string | null;
  errorKind: string | null;
  nextPoll: number | null;
  patientName: string | null;
  patientId: string | null;
  connections: { patientId: string; name: string }[];
  region: string;
  lastInserted: number;
  timeWarning: string | null;
}

const HOUR = 3600_000;

/**
 * FactoryTimestamp is assumed to be UTC. If the newest reading looks like it is
 * in the future, or exactly N hours old, that assumption is probably wrong.
 */
export function checkClockSkew(latestTs: number | null, now = Date.now()): string | null {
  if (latestTs === null) return null;
  const skew = now - latestTs;
  if (skew < -10 * 60_000) {
    return `最新讀值的時間比現在晚 ${(-skew / HOUR).toFixed(1)} 小時，時區解析可能錯誤。請把啟動 log 裡的 first graph item (raw) 拿來對照。`;
  }
  const hours = Math.round(skew / HOUR);
  if (hours >= 1 && Math.abs(skew - hours * HOUR) < 8 * 60_000) {
    return `最新讀值剛好是約 ${hours} 小時前，可能是時區解析錯誤（也可能是感測器暫時斷線）。若時間一直偏 ${hours} 小時，請回報 log 裡的 raw 資料。`;
  }
  return null;
}

const MIN = 60_000;

export class Poller {
  status: PollStatus = {
    state: "idle",
    lastAttempt: null,
    lastSuccess: null,
    lastError: null,
    errorKind: null,
    nextPoll: null,
    patientName: null,
    patientId: null,
    connections: [],
    region: "",
    lastInserted: 0,
    timeWarning: null,
  };
  private timer: ReturnType<typeof setTimeout> | null = null;
  private backoffMin: number;
  private loggedSample = false;
  private running: Promise<void> | null = null;

  constructor(
    private readonly client: LluClient,
    private readonly store: Store,
    private readonly opts: { intervalMin: number; patientId?: string; log?: (...a: unknown[]) => void },
  ) {
    this.backoffMin = opts.intervalMin;
  }

  private log(...a: unknown[]) {
    (this.opts.log ?? console.log)("[llu]", ...a);
  }

  start(): void {
    void this.pollNow();
  }

  stop(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  private schedule(minutes: number) {
    this.stop();
    this.status.nextPoll = Date.now() + minutes * MIN;
    this.timer = setTimeout(() => void this.pollNow(), minutes * MIN);
  }

  /** Run one poll. Concurrent callers share the same run. */
  pollNow(): Promise<void> {
    if (!this.running) this.running = this.run().finally(() => (this.running = null));
    return this.running;
  }

  private pickConnection(conns: Connection[]): Connection {
    if (!conns.length) {
      throw new LluError(
        "no_connections",
        "這個 LibreLinkUp 帳號沒有任何分享連線。請在 FreeStyle Libre App 的「連線的應用程式 → LibreLinkUp」邀請此帳號，並在 LibreLinkUp App 接受邀請。",
      );
    }
    if (this.opts.patientId) {
      const c = conns.find((x) => x.patientId === this.opts.patientId);
      if (!c) throw new LluError("config", `找不到 LLU_PATIENT_ID=${this.opts.patientId}，可用的連線見下方列表`);
      return c;
    }
    return conns[0]!;
  }

  private async run(): Promise<void> {
    this.status.lastAttempt = Date.now();
    try {
      const conns = await this.client.connections();
      this.status.connections = conns.map((c) => ({
        patientId: c.patientId,
        name: [c.firstName, c.lastName].filter(Boolean).join(" "),
      }));
      this.status.region = this.client.currentRegion;
      const conn = this.pickConnection(conns);
      this.status.patientId = conn.patientId;
      this.status.patientName = [conn.firstName, conn.lastName].filter(Boolean).join(" ") || null;

      const graph = await this.client.graph(conn.patientId);
      const items: any[] = Array.isArray(graph?.graphData) ? graph.graphData : [];
      if (!this.loggedSample) {
        this.log("first graph item (raw):", JSON.stringify(items[0] ?? null));
        this.log("latest measurement (raw):", JSON.stringify(graph?.connection?.glucoseMeasurement ?? null));
        this.loggedSample = true;
      }
      const rows: Reading[] = [];
      for (const it of items) {
        const r = itemToReading(it, "graph");
        if (r) rows.push(r);
      }
      const latest = itemToReading(graph?.connection?.glucoseMeasurement, "latest");
      if (latest) rows.push(latest);
      const newest = rows.reduce<number | null>((a, r) => (a === null || r.ts > a ? r.ts : a), null);
      this.status.timeWarning = checkClockSkew(newest);
      if (this.status.timeWarning) this.log("WARNING:", this.status.timeWarning);

      let logRows: Reading[] = [];
      try {
        const lb = await this.client.logbook(conn.patientId);
        logRows = lb.flatMap((it) => {
          const r = itemToReading(it, "logbook");
          return r ? [r] : [];
        });
      } catch (e) {
        this.log("logbook failed (ignored):", (e as Error).message);
      }

      if (items.length && rows.length === 0) {
        this.log("WARNING: graph items present but none parsed — timestamp format may have changed");
      }
      const n = this.store.upsertReadings([...logRows, ...rows]);
      this.status.lastInserted = n;
      this.status.lastSuccess = Date.now();
      this.status.lastError = null;
      this.status.errorKind = null;
      this.status.state = "ok";
      this.backoffMin = this.opts.intervalMin;
      this.store.setMeta("last_success", String(this.status.lastSuccess));
      this.log(`ok: ${rows.length} graph + ${logRows.length} logbook readings, ${n} rows written`);
      this.schedule(this.opts.intervalMin);
    } catch (e) {
      const err = e instanceof LluError ? e : new LluError("network", (e as Error).message);
      this.status.lastError = err.message;
      this.status.errorKind = err.kind;
      this.status.region = this.client.currentRegion;
      this.log(`error (${err.kind}):`, err.message, err.raw ? JSON.stringify(err.raw).slice(0, 300) : "");
      if (err.fatal) {
        this.status.state = "stopped";
        this.status.nextPoll = null;
        this.stop();
        return;
      }
      this.status.state = "error";
      this.backoffMin = Math.min(this.backoffMin * 2, 60);
      this.schedule(this.backoffMin);
    }
  }
}
