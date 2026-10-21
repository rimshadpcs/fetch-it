import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions";
import { FieldValue, Timestamp, type DocumentReference } from "firebase-admin/firestore";
import { db, userDoc } from "../lib/db.js";
import type { OfferClaim } from "../lib/sign.js";
import { money } from "../rules.js";
import { fetchLiveOffer } from "./live.js";
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

/** Default place for watch purchases: the one marked default, else the only one. */
async function defaultPlaceId(uid: string): Promise<string> {
  const places = await userDoc(uid).collection("places").get();
  const chosen = places.docs.find((d) => d.data().isDefault) ?? (places.size === 1 ? places.docs[0] : undefined);
  if (!chosen) throw new HttpsError("invalid-argument", "placeId is required (no default saved place).");
  return chosen.id;
}

/** Watch an offer from fetchItem/findSimilar; buy it through the house rules when it hits targetPrice. */
export const watchOffer = onCall<{ offerId: string; targetPrice: number; size?: string; placeId?: string }>(
  { secrets, cors: true },
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

    const ref = userDoc(uid).collection("watches").doc();
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
      startPrice: live.price,
      lastPrice: live.price,
      lastCheckedAt: Timestamp.now(),
      channel3Tracking: tracking,
      purchaseId: null,
      purchaseStatus: null,
      triggeredAt: null,
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    };
    await ref.set(watch);
    const now = live.price === null ? "not listed right now" : `now ${money(live.price, live.currency)}`;
    await watchActivity(uid, ref.id, watch, "watching", `Watching for ${money(targetPrice, live.currency)} or less (${now}).`, live.price);
    return { watchId: ref.id, currentPrice: live.price, targetPrice, currency: live.currency, channel3Tracking: tracking };
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
  const live = await fetchLiveOffer(clients().c3(uid), w);
  await ref.update({ lastPrice: live.price, lastCheckedAt: FieldValue.serverTimestamp(), updatedAt: FieldValue.serverTimestamp() });
  const result: WatchCheck = { uid, watchId: ref.id, title: w.title, price: live.price, targetPrice: w.targetPrice, triggered: false };
  if (live.price === null || live.price > w.targetPrice + 1e-9) return result;

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
  const purchase = await startPurchase(uid, w, { placeId: w.placeId, size: w.size, source: "watch", watchId: ref.id });
  await ref.update({ purchaseId: purchase.purchaseId, purchaseStatus: purchase.status, updatedAt: FieldValue.serverTimestamp() });
  return { ...result, triggered: true, purchase };
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
export const checkWatchesNow = onCall({ secrets, timeoutSeconds: 120, cors: true }, async (request) => {
  const uid = requireUid(request.auth?.uid);
  return { results: await runWatchChecks(uid) };
});
