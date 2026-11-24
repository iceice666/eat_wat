import { join, resolve } from "node:path";

const env = Bun.env;

function num(v: string | undefined, fallback: number): number {
  const n = Number(v);
  return v && Number.isFinite(n) && n > 0 ? n : fallback;
}

const dataDir = resolve(env.DATA_DIR || "./data");

export const config = {
  email: env.LLU_EMAIL?.trim() || "",
  password: env.LLU_PASSWORD || "",
  patientId: env.LLU_PATIENT_ID?.trim() || "",
  region: env.LLU_REGION?.trim().toLowerCase() || "",
  version: env.LLU_VERSION?.trim() || "4.16.0",
  pollMinutes: num(env.POLL_INTERVAL_MIN, 5),
  host: env.HOST?.trim() || "127.0.0.1",
  port: num(env.PORT, 3000),
  appToken: env.APP_TOKEN?.trim() || "",
  mock: env.LLU_MOCK === "1" || env.LLU_MOCK === "true",
  dataDir,
  dbPath: join(dataDir, "eat_wat.sqlite"),
  photoDir: join(dataDir, "photos"),
  publicDir: resolve(import.meta.dir, "../public"),
};

export const isLoopback = (h: string) => h === "127.0.0.1" || h === "localhost" || h === "::1";
