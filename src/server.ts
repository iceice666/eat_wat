import { mkdirSync, existsSync, unlinkSync } from "node:fs";
import { join } from "node:path";
import { timingSafeEqual } from "node:crypto";
import { config, isLoopback } from "./config.ts";
import { openDb, Store, type MealInput } from "./db.ts";
import { LluClient } from "./llu.ts";
import { createMockFetch, mockHostFor } from "./mock.ts";
import { Poller } from "./poller.ts";
import { analyzeMeal, relativeCurve } from "./analysis.ts";
import { parseLibreViewCsv } from "./csv.ts";

const MIN = 60_000;
const MEAL_KINDS = ["早餐", "午餐", "晚餐", "點心", "飲料", "宵夜", ""];
const IMAGE_TYPES: Record<string, string> = {
  "image/jpeg": "jpg",
  "image/png": "png",
  "image/webp": "webp",
  "image/gif": "gif",
  "image/heic": "heic",
  "image/heif": "heif",
};
const MAX_PHOTO = 10 * 1024 * 1024;
const MAX_CSV = 30 * 1024 * 1024;

if (!isLoopback(config.host) && !config.appToken) {
  console.error(`HOST=${config.host} 會讓區網內的裝置連得到這個伺服器。請同時設定 APP_TOKEN，或改回 HOST=127.0.0.1。`);
  process.exit(1);
}

mkdirSync(config.photoDir, { recursive: true });
const store = new Store(openDb(config.dbPath));

const client = new LluClient(
  config.mock
    ? { email: "mock@example.invalid", password: "mock", fetch: createMockFetch(), hostFor: mockHostFor, version: config.version }
    : { email: config.email, password: config.password, region: config.region, version: config.version },
);
const poller = new Poller(client, store, { intervalMin: config.pollMinutes, patientId: config.mock ? "" : config.patientId });

// ---------- helpers ----------

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });
const bad = (msg: string, status = 400) => json({ error: msg }, status);

function intParam(url: URL, name: string): number | null {
  const v = url.searchParams.get(name);
  if (v === null) return null;
  const n = Number(v);
  return Number.isFinite(n) ? Math.trunc(n) : null;
}

function safeEqual(a: string, b: string): boolean {
  const x = Buffer.from(a);
  const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

function cookie(req: Request, name: string): string | null {
  const c = req.headers.get("cookie") ?? "";
  for (const part of c.split(";")) {
    const [k, ...v] = part.trim().split("=");
    if (k === name) return decodeURIComponent(v.join("="));
  }
  return null;
}

function authorized(req: Request): boolean {
  if (!config.appToken) return true;
  const t = req.headers.get("x-app-token") ?? cookie(req, "eat_token");
  return !!t && safeEqual(t, config.appToken);
}

function validateMeal(b: any, partial: boolean): Partial<MealInput> | string {
  const out: Partial<MealInput> = {};
  if (b.ts !== undefined || !partial) {
    const ts = Number(b.ts);
    if (!Number.isFinite(ts) || ts < 946684800000 || ts > Date.now() + 2 * 86400_000) return "時間不正確";
    out.ts = Math.trunc(ts);
  }
  if (b.title !== undefined || !partial) {
    const title = String(b.title ?? "").trim();
    if (!title) return "請填寫吃了什麼";
    if (title.length > 200) return "名稱太長";
    out.title = title;
  }
  if (b.kind !== undefined) {
    const kind = String(b.kind);
    if (!MEAL_KINDS.includes(kind)) return "餐別不正確";
    out.kind = kind;
  } else if (!partial) out.kind = "";
  if (b.carbs !== undefined || !partial) {
    if (b.carbs === null || b.carbs === "" || b.carbs === undefined) out.carbs = null;
    else {
      const c = Number(b.carbs);
      if (!Number.isFinite(c) || c < 0 || c > 1000) return "醣類克數不正確";
      out.carbs = Math.round(c * 10) / 10;
    }
  }
  if (b.note !== undefined || !partial) {
    const note = String(b.note ?? "");
    if (note.length > 2000) return "備註太長";
    out.note = note;
  }
  return out;
}

async function savePhoto(f: File): Promise<string | Response> {
  const ext = IMAGE_TYPES[f.type];
  if (!ext) return bad("照片格式不支援");
  if (f.size > MAX_PHOTO) return bad("照片超過 10MB");
  const name = `${crypto.randomUUID()}.${ext}`;
  await Bun.write(join(config.photoDir, name), f);
  return name;
}

function removePhoto(name: string | null) {
  if (!name || !/^[\w-]+\.\w+$/.test(name)) return;
  const p = join(config.photoDir, name);
  if (existsSync(p)) unlinkSync(p);
}

/** Read a meal body from JSON or multipart (with optional photo). */
async function readMealBody(req: Request): Promise<{ body: any; photo: File | null; removePhoto: boolean }> {
  const ct = req.headers.get("content-type") ?? "";
  if (ct.includes("multipart/form-data")) {
    const fd = await req.formData();
    const body: any = {};
    for (const k of ["ts", "title", "kind", "carbs", "note"]) {
      const v = fd.get(k);
      if (typeof v === "string") body[k] = v;
    }
    const p = fd.get("photo");
    return {
      body,
      photo: p instanceof File && p.size > 0 ? p : null,
      removePhoto: fd.get("removePhoto") === "1",
    };
  }
  const body = await req.json().catch(() => ({}));
  return { body, photo: null, removePhoto: body?.removePhoto === true };
}

function mealWithAnalysis(mealId: number, withCurve: boolean) {
  const m = store.meal(mealId);
  if (!m) return null;
  return analyzeOne(m, withCurve);
}

function analyzeOne(m: ReturnType<Store["meal"]> & {}, withCurve: boolean) {
  const from = m.ts - 40 * MIN;
  const to = m.ts + 185 * MIN;
  const pts = store.readings(from, to);
  const others = store.meals(m.ts - 180 * MIN, m.ts + 180 * MIN).filter((x) => x.id !== m.id).map((x) => x.ts);
  const response = analyzeMeal(m.ts, pts, others);
  return {
    ...m,
    response,
    curve: withCurve ? relativeCurve(m.ts, pts, response.baseline) : undefined,
  };
}

// ---------- routes ----------

async function api(req: Request, url: URL): Promise<Response> {
  const p = url.pathname;
  const method = req.method;

  if (p === "/api/status" && method === "GET") {
    return json({
      mock: config.mock,
      configured: config.mock || (!!config.email && !!config.password),
      poll: poller.status,
      intervalMin: config.pollMinutes,
      readings: store.readingStats(),
      latest: store.latestReading(),
    });
  }

  if (p === "/api/sync" && method === "POST") {
    await poller.pollNow();
    return json({ poll: poller.status });
  }

  if (p === "/api/readings" && method === "GET") {
    const from = intParam(url, "from");
    const to = intParam(url, "to");
    if (from === null || to === null || to < from || to - from > 31 * 86400_000) return bad("from/to 不正確（最多 31 天）");
    return json(store.readings(from, to));
  }

  if (p === "/api/meals" && method === "GET") {
    const from = intParam(url, "from");
    const to = intParam(url, "to");
    if (from === null || to === null || to < from) return bad("from/to 不正確");
    return json(store.meals(from, to));
  }

  if (p === "/api/meals" && method === "POST") {
    const { body, photo } = await readMealBody(req);
    const v = validateMeal(body, false);
    if (typeof v === "string") return bad(v);
    let photoName: string | null = null;
    if (photo) {
      const r = await savePhoto(photo);
      if (r instanceof Response) return r;
      photoName = r;
    }
    const m = store.addMeal({ ...(v as MealInput), photo: photoName });
    return json(m, 201);
  }

  if (p === "/api/analysis" && method === "GET") {
    const from = intParam(url, "from");
    const to = intParam(url, "to");
    if (from === null || to === null || to < from || to - from > 400 * 86400_000) return bad("from/to 不正確");
    const withCurve = url.searchParams.get("curve") === "1";
    return json(store.meals(from, to).map((m) => analyzeOne(m, withCurve)));
  }

  const mm = p.match(/^\/api\/meals\/(\d+)(\/analysis)?$/);
  if (mm) {
    const id = Number(mm[1]);
    if (mm[2]) {
      if (method !== "GET") return bad("method not allowed", 405);
      const r = mealWithAnalysis(id, true);
      return r ? json(r) : bad("找不到這筆紀錄", 404);
    }
    if (method === "GET") {
      const m = store.meal(id);
      return m ? json(m) : bad("找不到這筆紀錄", 404);
    }
    if (method === "PUT") {
      const cur = store.meal(id);
      if (!cur) return bad("找不到這筆紀錄", 404);
      const { body, photo, removePhoto: rm } = await readMealBody(req);
      const v = validateMeal(body, true);
      if (typeof v === "string") return bad(v);
      const patch: Partial<MealInput> = { ...v };
      if (photo) {
        const r = await savePhoto(photo);
        if (r instanceof Response) return r;
        removePhoto(cur.photo);
        patch.photo = r;
      } else if (rm) {
        removePhoto(cur.photo);
        patch.photo = null;
      }
      return json(store.updateMeal(id, patch));
    }
    if (method === "DELETE") {
      const m = store.deleteMeal(id);
      if (!m) return bad("找不到這筆紀錄", 404);
      removePhoto(m.photo);
      return json({ ok: true });
    }
    return bad("method not allowed", 405);
  }

  if (p === "/api/import/csv" && method === "POST") {
    const len = Number(req.headers.get("content-length") ?? 0);
    if (len > MAX_CSV) return bad("檔案太大");
    let text: string;
    const ct = req.headers.get("content-type") ?? "";
    if (ct.includes("multipart/form-data")) {
      const f = (await req.formData()).get("file");
      if (!(f instanceof File)) return bad("請選擇 CSV 檔");
      if (f.size > MAX_CSV) return bad("檔案太大");
      text = await f.text();
    } else text = await req.text();
    const tz = intParam(url, "tz"); // minutes east of UTC
    const parsed = parseLibreViewCsv(text, tz !== null && Math.abs(tz) <= 14 * 60 ? tz : null);
    if (!parsed.readings.length) return bad("檔案裡沒有找到血糖資料，請確認是 LibreView 匯出的 CSV");
    const r = store.insertCsvReadings(parsed.readings);
    return json({
      ...r,
      parsed: parsed.readings.length,
      rowsTotal: parsed.rowsTotal,
      rowsSkipped: parsed.rowsSkipped,
      dateOrder: parsed.dateOrder,
      unit: parsed.unit,
      first: parsed.readings[0]!.ts,
      last: parsed.readings[parsed.readings.length - 1]!.ts,
    });
  }

  return bad("not found", 404);
}

const STATIC_TYPES: Record<string, string> = {
  html: "text/html; charset=utf-8",
  js: "text/javascript; charset=utf-8",
  css: "text/css; charset=utf-8",
  svg: "image/svg+xml",
  webmanifest: "application/manifest+json",
  png: "image/png",
};

async function serveStatic(path: string): Promise<Response> {
  const name = path === "/" ? "index.html" : path.slice(1);
  if (!/^[\w-]+\.\w+$/.test(name)) return new Response("not found", { status: 404 });
  const file = Bun.file(join(config.publicDir, name));
  if (!(await file.exists())) return new Response("not found", { status: 404 });
  const ext = name.split(".").pop()!;
  return new Response(file, {
    headers: { "content-type": STATIC_TYPES[ext] ?? "application/octet-stream", "cache-control": "no-cache" },
  });
}

const server = Bun.serve({
  hostname: config.host,
  port: config.port,
  maxRequestBodySize: MAX_CSV + 1024 * 1024,
  async fetch(req) {
    const url = new URL(req.url);

    // `/?token=...` sets the cookie once, so a phone bookmark keeps working.
    const qt = url.searchParams.get("token");
    if (config.appToken && qt !== null && url.pathname === "/") {
      if (!safeEqual(qt, config.appToken)) return new Response("token 錯誤", { status: 401 });
      return new Response(null, {
        status: 302,
        headers: {
          location: "/",
          "set-cookie": `eat_token=${encodeURIComponent(qt)}; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000`,
        },
      });
    }

    const isPublicAsset = ["/manifest.webmanifest", "/icon.svg"].includes(url.pathname);
    if (!isPublicAsset && !authorized(req)) {
      if (url.pathname.startsWith("/api/")) return bad("unauthorized", 401);
      return new Response("需要 token：請用 http://<host>:<port>/?token=<APP_TOKEN> 開啟一次", {
        status: 401,
        headers: { "content-type": "text/plain; charset=utf-8" },
      });
    }

    try {
      if (url.pathname.startsWith("/api/")) return await api(req, url);
      const ph = url.pathname.match(/^\/photos\/([\w-]+\.\w+)$/);
      if (ph) {
        const f = Bun.file(join(config.photoDir, ph[1]!));
        return (await f.exists()) ? new Response(f) : new Response("not found", { status: 404 });
      }
      return await serveStatic(url.pathname);
    } catch (e) {
      console.error(e);
      return bad("伺服器錯誤", 500);
    }
  },
});

console.log(`eat_wat 在 http://${server.hostname}:${server.port} ${config.mock ? "（MOCK 模式，假資料）" : ""}`);
console.log(`資料目錄：${config.dataDir}`);
poller.start();
