// Credit protection while App Check isn't enforced (judges sideload the APK, so Play Integrity can't vouch for it):
// - per-user rate limits on the callables that spend credits (fixed hour/day windows in limits/{uid})
// - a global daily circuit breaker per paid API (usage/{YYYY-MM-DD}), caps set by params.
// Both docs are top-level, so clients can't read them (firestore.rules denies everything outside users/{uid}).

import { HttpsError } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { defineInt } from "firebase-functions/params";
import { FieldValue } from "firebase-admin/firestore";
import { db, userDoc } from "./lib/db.js";
import { meter, type Service } from "./lib/meter.js";

/** Daily caps on calls to each paid API, across all users (UTC day). Set in .env.fetchit-437a0 and redeploy. */
export const DAILY_CAP = {
  gemini: defineInt("DAILY_CAP_GEMINI", { default: 3000 }),
  claude: defineInt("DAILY_CAP_CLAUDE", { default: 1500 }),
  channel3: defineInt("DAILY_CAP_CHANNEL3", { default: 1000 }),
} satisfies Record<Service, unknown>;

/** Calls per user per window. A fetch with "Which one?" is two fetchItem calls. */
export const RATE_LIMITS = {
  fetchItem: { hour: 30, day: 200 },
  findSimilar: { hour: 30 },
  purchaseOffer: { hour: 20 },
  linkPhoto: { hour: 30 },
} satisfies Record<string, { hour?: number; day?: number }>;
export type LimitedAction = keyof typeof RATE_LIMITS;

const dayKey = (d = new Date()) => d.toISOString().slice(0, 10);
const hourKey = (d = new Date()) => d.toISOString().slice(0, 13);

interface Window {
  hour: string;
  hourCount: number;
  day: string;
  dayCount: number;
}

/** The user's dog, for the nap message. Read only when a limit is hit. */
async function dogName(uid: string): Promise<string> {
  const p = await userDoc(uid).collection("profile").doc("main").get().catch(() => null);
  const name = p?.data()?.dogName;
  return typeof name === "string" && name ? name : "Scout";
}

/**
 * Counts this call against the user's limits for `action`, or throws resource-exhausted with the nap message.
 * Fails open: if Firestore itself errors, the call goes through (logged).
 */
export async function rateLimit(uid: string, action: LimitedAction): Promise<void> {
  const limits: { hour?: number; day?: number } = RATE_LIMITS[action];
  const ref = db.collection("limits").doc(uid);
  const now = new Date();
  let hit: "hour" | "day" | null = null;
  try {
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      const prev = (snap.data()?.[action] as Window | undefined) ?? null;
      const w: Window = {
        hour: hourKey(now),
        hourCount: prev?.hour === hourKey(now) ? prev.hourCount : 0,
        day: dayKey(now),
        dayCount: prev?.day === dayKey(now) ? prev.dayCount : 0,
      };
      hit = limits.hour !== undefined && w.hourCount >= limits.hour ? "hour" : limits.day !== undefined && w.dayCount >= limits.day ? "day" : null;
      if (hit) return;
      tx.set(ref, { [action]: { ...w, hourCount: w.hourCount + 1, dayCount: w.dayCount + 1 }, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    });
  } catch (e) {
    logger.warn("rate limit check failed, letting the call through", { uid, action, message: (e as Error).message });
    return;
  }
  if (!hit) return;
  logger.warn("rate limited", { uid, action, window: hit, limit: limits[hit] });
  const name = await dogName(uid);
  throw new HttpsError(
    "resource-exhausted",
    hit === "hour" ? `${name} needs a quick nap, try again in a few minutes.` : `${name} needs a long nap, try again tomorrow.`,
    { reason: "rate_limit", action, window: hit },
  );
}

// ---- Daily circuit breaker ----

/** This instance's view of today's counts: re-read every REFRESH_MS (always in the emulator, for tests), plus this instance's own calls since. */
const REFRESH_MS = process.env.FUNCTIONS_EMULATOR === "true" ? 0 : 15_000;
let cache: { day: string; counts: Record<Service, number>; readAt: number } | null = null;
const trippedLogged = new Set<string>();

async function counts(): Promise<Record<Service, number>> {
  const day = dayKey();
  if (!cache || cache.day !== day || Date.now() - cache.readAt > REFRESH_MS) {
    const d = (await db.collection("usage").doc(day).get()).data() ?? {};
    cache = { day, counts: { gemini: d.gemini ?? 0, claude: d.claude ?? 0, channel3: d.channel3 ?? 0 }, readAt: Date.now() };
  }
  return cache.counts;
}

function record(service: Service) {
  const day = dayKey();
  if (cache?.day === day) cache.counts[service]++;
  db.collection("usage").doc(day).set({ [service]: FieldValue.increment(1) }, { merge: true }).catch((e) => {
    logger.warn("usage count failed", { service, message: (e as Error).message });
  });
}
meter.record = record;

/** True when today's calls to `service` reached its cap. Logs (and marks usage/{day}) the first time per instance. */
export async function tripped(service: Service): Promise<boolean> {
  let count: number;
  try {
    count = (await counts())[service];
  } catch (e) {
    logger.warn("circuit breaker read failed, letting the call through", { service, message: (e as Error).message });
    return false;
  }
  const cap = DAILY_CAP[service].value();
  if (count < cap) return false;
  const key = `${dayKey()}:${service}`;
  if (!trippedLogged.has(key)) {
    trippedLogged.add(key);
    logger.error("circuit breaker tripped", { service, count, cap, day: dayKey() });
    db.collection("usage").doc(dayKey()).set({ tripped: { [service]: FieldValue.serverTimestamp() } }, { merge: true }).catch(() => {});
  }
  return true;
}

/** Throws the "long nap" error if any of `services` is over today's cap. */
export async function requireServices(uid: string, services: Service[]): Promise<void> {
  for (const s of services) {
    if (await tripped(s)) {
      const name = await dogName(uid);
      throw new HttpsError("resource-exhausted", `${name} needs a long nap, try again tomorrow.`, { reason: "daily_cap", service: s });
    }
  }
}
