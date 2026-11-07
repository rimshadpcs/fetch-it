import { HttpsError, onCall } from "firebase-functions/v2/https";
import { ENFORCE_APP_CHECK } from "../options.js";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions";
import { FieldValue, Timestamp, type DocumentReference } from "firebase-admin/firestore";
import { db, userDoc } from "../lib/db.js";
import type { OfferClaim } from "../lib/sign.js";
import { money } from "../rules.js";
import { fetchLiveOffer } from "./live.js";
import { dropToNotify, summarizeHistory, type HistorySummary } from "../lib/pricehistory.js";
import { notify } from "../push.js";
import { clients, requireUid, secrets, startPurchase, verifyOffer, type PurchaseResponse } from "./purchase.js";

export interface WatchDoc {
  status: "watching" | "triggered" | "stopped";
  productId: string;
  merchant: string;
  color: OfferClaim["color"];
  title: string;
  imageUrl: string | null;
  targetPrice: number;
  currency: string;
  size: string | null;
  placeId: string;
  startPrice: number | null;
  lastPrice: number | null;
  lastCheckedAt: Timestamp | null;
  /** Channel3 price-tracking subscription state (history + future webhooks). */
  channel3Tracking: string;
  purchaseId: string | null;
  purchaseStatus: string | null;
  /** Last 30 days of Channel3 price history, summarised (sparkline + buy-now-or-wait); refreshed ≤ every 12 h. */
  history?: HistorySummary | null;
  historyAt?: Timestamp | null;
  /** Lowest price we've pushed a "price dropped" for while still above the target (so a bounce doesn't re-notify). */
  notifiedPrice?: number | null;
  triggeredAt: Timestamp | null;
  createdAt: FieldValue | Timestamp;
  updatedAt: FieldValue | Timestamp;
}

const watchActivity = (uid: string, watchId: string, w: Partial<WatchDoc>, status: string, summary: string, price: number | null) =>
  userDoc(uid).collection("activity").add({
    type: "watch",
    watchId,
    status,
    summary,
    title: w.title ?? null,
    retailer: w.merchant ?? null,
    price,
    targetPrice: w.targetPrice ?? null,
    currency: w.currency ?? "USD",
    imageUrl: w.imageUrl ?? null,
    createdAt: FieldValue.serverTimestamp(),
  });

const HISTORY_EVERY_MS = 12 * 60 * 60 * 1000;

/** Fetch + summarise the product's price history onto the watch. Best effort: a failure never blocks the watch. */
async function refreshHistory(c3: ReturnType<ReturnType<typeof clients>["c3"]>, ref: DocumentReference, productId: string, now: number | null) {
  try {
    const { history } = await c3.priceHistory(productId);
    await ref.update({ history: summarizeHistory(history ?? [], now), historyAt: FieldValue.serverTimestamp() });
  } catch (e) {
    logger.warn("Channel3 price history failed", { productId, message: (e as Error).message });
  }
}

/** Default place for watch purchases: the one marked default, else the only one. */
async function defaultPlaceId(uid: string): Promise<string> {
  const places = await userDoc(uid).collection("places").get();
  const chosen = places.docs.find((d) => d.data().isDefault) ?? (places.size === 1 ? places.docs[0] : undefined);
  if (!chosen) throw new HttpsError("invalid-argument", "placeId is required (no default saved place).");
  return chosen.id;
}

/** Watch an offer from fetchItem/findSimilar; buy it through the house rules when it hits targetPrice. */
export const watchOffer = onCall<{ offerId: string; targetPrice: number; size?: string; placeId?: string }>(
  { secrets, cors: true, enforceAppCheck: ENFORCE_APP_CHECK },
  async (request) => {
    const uid = requireUid(request.auth?.uid);
    const { offerId, targetPrice, size, placeId } = request.data ?? {};
    if (typeof targetPrice !== "number" || !(targetPrice > 0)) throw new HttpsError("invalid-argument", "targetPrice must be a positive number.");
    if (size !== undefined && typeof size !== "string") throw new HttpsError("invalid-argument", "size must be a string.");
    const claim = verifyOffer(offerId, uid);
    const place = typeof placeId === "string" && placeId ? placeId : await defaultPlaceId(uid);

    const c3 = clients().c3(uid);
    const live = await fetchLiveOffer(c3, claim);
    // Subscriptions are per Channel3 API key (shared by all users), so we never stop them per user.
    const tracking = await c3
      .startTracking(claim.productId)
      .then((r) => r.subscription_status)
      .catch((e) => {
        logger.warn("Channel3 startTracking failed", { productId: claim.productId, message: (e as Error).message });
        return "failed";
      });

    // One watch per item: watching it again just changes the target (and the place / size).
    const existing = await userDoc(uid).collection("watches")
      .where("status", "==", "watching").where("productId", "==", claim.productId).where("merchant", "==", claim.merchant).limit(1).get();
    const ref = existing.empty ? userDoc(uid).collection("watches").doc() : existing.docs[0].ref;
    const watch: WatchDoc = {
      status: "watching",
      productId: claim.productId,
      merchant: claim.merchant,
      color: claim.color,
      title: live.title,
      imageUrl: live.imageUrl,
      targetPrice,
      currency: live.currency,
      size: size ?? null,
      placeId: place,
      startPrice: existing.empty ? live.price : ((existing.docs[0].data().startPrice as number | null) ?? live.price),
      lastPrice: live.price,
      notifiedPrice: existing.empty ? null : ((existing.docs[0].data().notifiedPrice as number | null) ?? null),
      lastCheckedAt: Timestamp.now(),
      channel3Tracking: tracking,
      purchaseId: null,
      purchaseStatus: null,
      triggeredAt: null,
      createdAt: existing.empty ? FieldValue.serverTimestamp() : (existing.docs[0].data().createdAt as Timestamp),
      updatedAt: FieldValue.serverTimestamp(),
    };
    await ref.set(watch);
    await refreshHistory(c3, ref, claim.productId, live.price);
    const now = live.price === null ? "not listed right now" : `now ${money(live.price, live.currency)}`;
    await watchActivity(uid, ref.id, watch, "watching", `Watching for ${money(targetPrice, live.currency)} or less (${now}).`, live.price);
    return { watchId: ref.id, currentPrice: live.price, targetPrice, currency: live.currency, channel3Tracking: tracking };
  },
);

/** Stop watching (only a watch that's still watching; a triggered one has already gone to the rules). */
export const stopWatch = onCall<{ watchId: string }>(
  { cors: true, enforceAppCheck: ENFORCE_APP_CHECK },
  async (request) => {
    const uid = requireUid(request.auth?.uid);
    const watchId = request.data?.watchId;
    if (typeof watchId !== "string" || !/^[A-Za-z0-9]{1,64}$/.test(watchId)) throw new HttpsError("invalid-argument", "watchId is required.");
    const ref = userDoc(uid).collection("watches").doc(watchId);
    const w = await db.runTransaction(async (tx) => {
      const cur = (await tx.get(ref)).data() as WatchDoc | undefined;
      if (!cur) throw new HttpsError("not-found", "No such watch.");
      if (cur.status !== "watching") throw new HttpsError("failed-precondition", `This watch is already ${cur.status}.`);
      tx.update(ref, { status: "stopped", updatedAt: FieldValue.serverTimestamp() });
      return cur;
    });
    await watchActivity(uid, watchId, w, "stopped", `Stopped watching (target was ${money(w.targetPrice, w.currency)}).`, w.lastPrice);
    return { watchId, status: "stopped" };
  },
);

export interface WatchCheck {
  uid: string;
  watchId: string;
  title: string;
  price: number | null;
  targetPrice: number;
  triggered: boolean;
  purchase?: PurchaseResponse;
}

async function checkWatch(uid: string, ref: DocumentReference, w: WatchDoc): Promise<WatchCheck> {
  const c3 = clients().c3(uid);
  const live = await fetchLiveOffer(c3, w);
  await ref.update({ lastPrice: live.price, lastCheckedAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() });
  if (!w.historyAt || Date.now() - w.historyAt.toMillis() > HISTORY_EVERY_MS) await refreshHistory(c3, ref, w.productId, live.price);
  const result: WatchCheck = { uid, watchId: ref.id, title: w.title, price: live.price, targetPrice: w.targetPrice, triggered: false };
  if (live.price === null) return result;
  if (live.price > w.targetPrice + 1e-9) {
    await maybeNotifyDrop(uid, ref, w, live.price, live.currency);
    return result;
  }

  // Claim the watch so two overlapping checks can't both buy.
  const claimed = await db.runTransaction(async (tx) => {
    const cur = (await tx.get(ref)).data() as WatchDoc | undefined;
    if (cur?.status !== "watching") return false;
    tx.update(ref, { status: "triggered", triggeredAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() });
    return true;
  });
  if (!claimed) return result;

  // Logged whatever the rules decide next.
  await watchActivity(
    uid,
    ref.id,
    w,
    "price_dropped",
    `Price dropped to ${money(live.price, live.currency)} (your target ${money(w.targetPrice, w.currency)}). Checking your house rules.`,
    live.price,
  );
  const purchase = await startPurchase(uid, w, { placeId: w.placeId, size: w.size, source: "watch", watchId: ref.id, priceWas: w.startPrice });
  await ref.update({ purchaseId: purchase.purchaseId, purchaseStatus: purchase.status, updatedAt: FieldValue.serverTimestamp() });
  return { ...result, triggered: true, purchase };
}

async function maybeNotifyDrop(uid: string, ref: DocumentReference, w: WatchDoc, price: number, currency: string) {
  const was = dropToNotify(w, price);
  if (was === null) return;
  await ref.update({ notifiedPrice: price });
  const off = Math.round(((w.startPrice ?? was) - price) / (w.startPrice ?? was) * 100);
  await notify(uid, {
    title: `Price dropped: ${w.title} · ${money(price, currency)}`,
    body: `Down from ${money(was, currency)}${off >= 1 ? ` (${off}% off since you started watching)` : ""}. Still above your ${money(w.targetPrice, w.currency)} target, so I'll keep watching.`,
    open: "watch",
    watchId: ref.id,
  });
}

/** Check every active watch (or one user's). A few at a time: auto-buys wait out their undo window. */
export async function runWatchChecks(uid?: string): Promise<WatchCheck[]> {
  const query = uid
    ? userDoc(uid).collection("watches").where("status", "==", "watching")
    : db.collectionGroup("watches").where("status", "==", "watching");
  const snap = await query.get();
  const results: WatchCheck[] = [];
  const docs = snap.docs;
  for (let i = 0; i < docs.length; i += 5) {
    const batch = await Promise.all(
      docs.slice(i, i + 5).map((d) =>
        checkWatch(d.ref.parent.parent!.id, d.ref, d.data() as WatchDoc).catch((e) => {
          logger.error("watch check failed", { path: d.ref.path, message: (e as Error).message });
          return null;
        }),
      ),
    );
    results.push(...batch.filter((r): r is WatchCheck => r !== null));
  }
  logger.info("watch checks", { checked: results.length, triggered: results.filter((r) => r.triggered).length });
  return results;
}

/** Hourly in production. */
export const checkWatches = onSchedule({ schedule: "every 60 minutes", secrets, timeoutSeconds: 540 }, async () => {
  await runWatchChecks();
});

/** "Check now" for the signed-in user's own watches (also how the emulator test runs a check on demand). */
export const checkWatchesNow = onCall({ secrets, timeoutSeconds: 120, cors: true, enforceAppCheck: ENFORCE_APP_CHECK }, async (request) => {
  const uid = requireUid(request.auth?.uid);
  return { results: await runWatchChecks(uid) };
});
