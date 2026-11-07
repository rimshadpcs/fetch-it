// Saved for later (the heart on a result): users/{uid}/saved/{savedId}. Saving needs a valid signed offer, so
// only things the user was actually shown can be saved; buying one later goes through savedOffer, which re-checks
// the live price at the same store and signs a fresh offer for the normal purchase path (rules apply as usual).

import { HttpsError, onCall } from "firebase-functions/v2/https";
import { FieldValue } from "firebase-admin/firestore";
import { CHANNEL3_API_KEY, FETCH_SIGNING_KEY } from "./config.js";
import { ENFORCE_APP_CHECK } from "./options.js";
import { userDoc } from "./lib/db.js";
import { sha256, sign, TOKEN_TTL_MS, type OfferClaim } from "./lib/sign.js";
import { clients, requireUid, verifyOffer } from "./purchase/purchase.js";
import { fetchLiveOffer } from "./purchase/live.js";

export const MAX_SAVED = 100;

/** One id per product + store + colour, so saving the same thing twice is one entry. */
const savedId = (c: Pick<OfferClaim, "productId" | "merchant" | "color">) => sha256(`${c.productId}|${c.merchant}|${c.color?.label ?? ""}`).slice(0, 24);
const savedRef = (uid: string, id: string) => userDoc(uid).collection("saved").doc(id);

/**
 * `{offerId, saved}`: heart (saved: true, stores the live price now) or un-heart (saved: false, deletes it).
 * Un-hearting from the Saved list can send `{savedId, saved: false}` instead (its offer token may have expired).
 */
export const saveItem = onCall<{ offerId?: string; savedId?: string; saved: boolean }>(
  { secrets: [CHANNEL3_API_KEY, FETCH_SIGNING_KEY], cors: true, enforceAppCheck: ENFORCE_APP_CHECK },
  async (request) => {
    const uid = requireUid(request.auth?.uid);
    const byId = request.data?.savedId;
    if (request.data?.saved === false && request.data?.offerId === undefined && typeof byId === "string" && /^[A-Za-z0-9_-]{1,40}$/.test(byId)) {
      await savedRef(uid, byId).delete();
      return { savedId: byId, saved: false };
    }
    const claim = verifyOffer(request.data?.offerId, uid);
    if (typeof request.data?.saved !== "boolean") throw new HttpsError("invalid-argument", "saved must be true or false.");
    const id = savedId(claim);
    if (!request.data.saved) {
      await savedRef(uid, id).delete();
      return { savedId: id, saved: false };
    }
    const count = (await userDoc(uid).collection("saved").count().get()).data().count;
    if (count >= MAX_SAVED && !(await savedRef(uid, id).get()).exists) throw new HttpsError("resource-exhausted", `Up to ${MAX_SAVED} saved things.`);
    const live = await fetchLiveOffer(clients().c3(uid), claim).catch(() => null); // detail is free; a hiccup still saves
    await savedRef(uid, id).set({
      productId: claim.productId, merchant: claim.merchant, color: claim.color, title: live?.title ?? claim.title,
      imageUrl: live?.imageUrl ?? claim.imageUrl, url: live?.url ?? null, price: live?.price ?? null, currency: live?.currency ?? "USD",
      savedAt: FieldValue.serverTimestamp(),
    });
    return { savedId: id, saved: true };
  },
);

/** `{savedId}` → a fresh signed offer (same store and colour) with the live price, stock and sizes, ready to buy. */
export const savedOffer = onCall<{ savedId: string }>(
  { secrets: [CHANNEL3_API_KEY, FETCH_SIGNING_KEY], cors: true, enforceAppCheck: ENFORCE_APP_CHECK },
  async (request) => {
    const uid = requireUid(request.auth?.uid);
    const id = request.data?.savedId;
    if (typeof id !== "string" || !/^[A-Za-z0-9_-]{1,40}$/.test(id)) throw new HttpsError("invalid-argument", "savedId is required.");
    const snap = await savedRef(uid, id).get();
    if (!snap.exists) throw new HttpsError("not-found", "That's no longer saved.");
    const s = snap.data()!;
    const claim: Omit<OfferClaim, "kind" | "uid" | "exp"> = { productId: s.productId, merchant: s.merchant, color: s.color ?? null, title: s.title, imageUrl: s.imageUrl ?? null };
    const live = await fetchLiveOffer(clients().c3(uid), claim);
    if (live.price !== null) await snap.ref.update({ price: live.price, currency: live.currency, checkedAt: FieldValue.serverTimestamp() });
    return {
      offerId: sign(FETCH_SIGNING_KEY.value(), { kind: "offer", uid, ...claim, exp: Date.now() + TOKEN_TTL_MS } satisfies OfferClaim),
      productId: live.productId, title: live.title, merchant: live.merchant, url: live.url ?? "", imageUrl: live.imageUrl,
      price: live.price ?? 0, currency: live.currency, availability: live.inStock ? "InStock" : "OutOfStock",
      color: live.color, sizes: live.sizes?.map((z) => ({ label: z.label, size: z.size, available: z.available ?? null })) ?? null,
      inStock: live.inStock,
    };
  },
);
