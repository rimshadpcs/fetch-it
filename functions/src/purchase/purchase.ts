import { randomUUID } from "node:crypto";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { FieldValue, Timestamp, type DocumentReference, type Transaction } from "firebase-admin/firestore";
import {
  CHANNEL3_API_KEY,
  FETCH_SIGNING_KEY,
  PAYPAL_BASE_URL,
  PAYPAL_CLIENT_ID,
  PAYPAL_CLIENT_SECRET,
} from "../config.js";
import { Channel3 } from "../lib/channel3.js";
import { db, monthKey, userDoc } from "../lib/db.js";
import { PayPal, PayPalError, type ShippingAddress } from "../lib/paypal.js";
import { InvalidToken, verify, type OfferClaim } from "../lib/sign.js";
import { decide, money, type Decision, type HouseRules, type Reason } from "../rules.js";
import { fetchLiveOffer, type LiveOffer } from "./live.js";
import { placeDemoOrder } from "../fulfillment.js";

/** Seconds between "Fetched!" and the charge, while the app shows Undo. */
export const UNDO_SECONDS = 5;

/**
 * Hackathon demo: PayPal charges go to our sandbox merchant, and no order is placed with the real store.
 * Every purchase says so, and the app shows "Demo store · sandbox" from these fields.
 */
export const MERCHANT_OF_RECORD = "Fetch It demo store (PayPal sandbox)";
export const RETAILER_ORDER_STATUS = "simulated";

export type PurchaseStatus =
  | "blocked" // rules said no; nothing charged
  | "needs_approval" // waiting for the user's fingerprint
  | "declined" // user said no to an approval request
  | "pending_undo" // auto-buy inside the undo window
  | "undone" // user hit Undo in time; nothing charged
  | "approved" // user approved; about to charge
  | "charging"
  | "completed"
  | "failed" // PayPal declined or errored; nothing charged
  | "refunded"; // order cancelled before shipping; capture refunded

interface Place extends ShippingAddress {
  label: string;
}

export interface PurchaseDoc {
  status: PurchaseStatus;
  /** Who takes the money: always our sandbox demo store. */
  merchantOfRecord: string;
  /** The real store Channel3 found the offer at (its domain, e.g. "wayfair.com"). */
  retailer: string;
  /** No real order is placed with the retailer in the demo. */
  retailerOrderStatus: typeof RETAILER_ORDER_STATUS;
  /** "user" from the app, "watch" when a price watch hit its target. */
  source: "user" | "watch";
  watchId: string | null;
  decision: Decision;
  reasons: Reason[];
  productId: string;
  merchant: string;
  title: string;
  imageUrl: string | null;
  url: string | null;
  color: { option: string; label: string } | null;
  size: string | null;
  /** Live detail price; null only when the store no longer lists it (always blocked). */
  price: number | null;
  currency: string;
  categoryPath: string[];
  placeId: string;
  placeLabel: string | null;
  shipTo: ShippingAddress | null;
  /** Month whose spend this purchase reserved, so undo/failure can release it. */
  spendMonth: string | null;
  undoUntil: Timestamp | null;
  paypalOrderId: string | null;
  paypalCaptureId: string | null;
  error: string | null;
  /** Which charge attempt owns the `charging` state (normal path or stuck-purchase cleanup). */
  chargeAttempt?: string | null;
  chargeStartedAt?: Timestamp | FieldValue | null;
  chargeAttempts?: number | FieldValue;
  /** Set when the stuck-purchase cleanup finished this purchase. */
  recoveredBy?: string | null;
  createdAt: FieldValue | Timestamp;
  updatedAt: FieldValue | Timestamp;
}

const SUMMARY: Record<PurchaseStatus, string> = {
  blocked: "Blocked by your house rules. Nothing charged.",
  needs_approval: "Needs your OK.",
  declined: "You said no. Nothing charged.",
  pending_undo: `Buying in ${UNDO_SECONDS} seconds. Tap Undo to stop it.`,
  undone: "Undone. Nothing charged.",
  approved: "Approved. Paying with PayPal.",
  charging: "Paying with PayPal.",
  completed: "Fetched! Paid with PayPal.",
  failed: "Payment didn't go through. Nothing charged.",
  refunded: "Cancelled and refunded.",
};

/** One Activity entry per decision or event, so the log reads as a history. */
function logActivity(tx: Transaction, uid: string, purchaseId: string, p: Partial<PurchaseDoc> & { status: PurchaseStatus }) {
  tx.set(userDoc(uid).collection("activity").doc(), {
    type: "purchase",
    purchaseId,
    status: p.status,
    summary: SUMMARY[p.status],
    decision: p.decision ?? null,
    reasons: p.reasons ?? [],
    title: p.title ?? null,
    merchant: p.merchant ?? null,
    retailer: p.retailer ?? p.merchant ?? null,
    merchantOfRecord: p.merchantOfRecord ?? MERCHANT_OF_RECORD,
    retailerOrderStatus: p.retailerOrderStatus ?? RETAILER_ORDER_STATUS,
    source: p.source ?? "user",
    watchId: p.watchId ?? null,
    price: p.price ?? null,
    currency: p.currency ?? null,
    imageUrl: p.imageUrl ?? null,
    size: p.size ?? null,
    placeLabel: p.placeLabel ?? null,
    paypalOrderId: p.paypalOrderId ?? null,
    error: p.error ?? null,
    ...(p.recoveredBy && { recoveredBy: p.recoveredBy }),
    createdAt: FieldValue.serverTimestamp(),
  });
}

/** Everything the rules need from Firestore, read inside the transaction (reads before writes). */
async function readContext(tx: Transaction, uid: string, placeId: string) {
  const user = userDoc(uid);
  const month = monthKey();
  const [rulesSnap, placeSnap, storesSnap, spendSnap, vaultSnap] = await Promise.all([
    tx.get(user.collection("settings").doc("rules")),
    tx.get(user.collection("places").doc(placeId)),
    tx.get(user.collection("stores")),
    tx.get(user.collection("spend").doc(month)),
    tx.get(db.collection("vault").doc(uid)),
  ]);
  if (!rulesSnap.exists) throw new HttpsError("failed-precondition", "Set your house rules first.");
  // A place PayPal only suggested (link flow) isn't saved until the user confirms it.
  const place = placeSnap.exists && !placeSnap.data()?.suggested ? (placeSnap.data() as Place) : null;
  return {
    month,
    rules: rulesSnap.data() as HouseRules,
    place,
    knownStores: storesSnap.docs.map((d) => d.data().merchant as string),
    monthToDate: ((spendSnap.data()?.totalCents as number | undefined) ?? 0) / 100,
    vaultId: (vaultSnap.data()?.paypalVaultId as string | undefined) ?? null,
  };
}

const spendRef = (uid: string, month: string) => userDoc(uid).collection("spend").doc(month);
/** Spend is kept in integer cents: Firestore float increments drift (212.98000000000002). */
const reserve = (tx: Transaction, uid: string, month: string, amount: number, currency: string) =>
  tx.set(
    spendRef(uid, month),
    { totalCents: FieldValue.increment(Math.round(amount * 100)), currency, updatedAt: FieldValue.serverTimestamp() },
    { merge: true },
  );

export function clients() {
  return {
    c3: (uid: string) => new Channel3(CHANNEL3_API_KEY.value(), uid),
    paypal: () => new PayPal(PAYPAL_BASE_URL, PAYPAL_CLIENT_ID.value(), PAYPAL_CLIENT_SECRET.value()),
  };
}

/** Who may move a purchase into `charging`. */
export type ClaimRule = (p: PurchaseDoc, now: number) => boolean;
export const claimIf =
  (status: PurchaseStatus): ClaimRule =>
  (p) =>
    p.status === status;

/**
 * Claim a purchase (status check + flip to `charging` + attempt id, in one transaction), charge the vault,
 * record the result. Only the attempt that holds the claim may write the outcome, and the completion
 * transaction creates the demo order, so a purchase is charged and fulfilled at most once even when the
 * normal path and the stuck-purchase cleanup overlap. PayPal-Request-Id = purchase id backs this up: a
 * retried charge returns the same PayPal order instead of a new one.
 */
export async function charge(uid: string, ref: DocumentReference, canClaim: ClaimRule, recoveredBy: string | null = null): Promise<PurchaseDoc> {
  const attempt = randomUUID();
  const start = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const p = snap.data() as PurchaseDoc;
    const vault = await tx.get(db.collection("vault").doc(uid));
    if (!p || !canClaim(p, Date.now())) return { p, vaultId: null, claimed: false };
    tx.update(ref, {
      status: "charging",
      chargeAttempt: attempt,
      chargeStartedAt: FieldValue.serverTimestamp(),
      chargeAttempts: FieldValue.increment(1),
      updatedAt: FieldValue.serverTimestamp(),
    });
    return { p: { ...p, status: "charging" as const }, vaultId: (vault.data()?.paypalVaultId as string) ?? null, claimed: true };
  });
  if (!start.claimed) return start.p; // undone / declined / someone else is charging it

  const p = start.p;
  let outcome: Partial<PurchaseDoc> & { status: PurchaseStatus };
  try {
    if (!start.vaultId) throw new PayPalError("No linked PayPal account", 0, null, "NO_VAULT");
    if (!p.shipTo) throw new PayPalError("No shipping address", 0, null, "NO_ADDRESS");
    const result = await clients()
      .paypal()
      .chargeVaulted({
        requestId: ref.id, // idempotent: a retry of this purchase can't charge twice
        vaultId: start.vaultId,
        amount: p.price!,
        currency: p.currency,
        description: `${p.title} (${p.merchant}) · Fetch It demo store`,
        shipTo: p.shipTo,
      });
    outcome =
      result.status === "COMPLETED"
        ? { status: "completed", paypalOrderId: result.orderId, paypalCaptureId: result.captureId, error: null }
        : { status: "failed", paypalOrderId: result.orderId, error: `PayPal order ${result.status}` };
  } catch (e) {
    const err = e as Error;
    logger.error("charge failed", { uid, purchaseId: ref.id, ...(e instanceof PayPalError ? e.toLog() : { message: err.message }) });
    outcome = { status: "failed", error: err.message };
  }
  if (recoveredBy) outcome.recoveredBy = recoveredBy;

  return db.runTransaction(async (tx) => {
    const cur = (await tx.get(ref)).data() as PurchaseDoc;
    if (cur.status !== "charging" || cur.chargeAttempt !== attempt) return cur; // another attempt owns it now
    const order = outcome.status === "completed" ? await tx.get(userDoc(uid).collection("orders").doc(ref.id)) : null;
    tx.update(ref, { ...outcome, updatedAt: FieldValue.serverTimestamp() });
    const done = { ...p, ...outcome } as PurchaseDoc;
    if (outcome.status === "completed") {
      tx.set(userDoc(uid).collection("stores").doc(p.merchant), { merchant: p.merchant, lastPurchaseAt: FieldValue.serverTimestamp() }, { merge: true });
    } else if (p.spendMonth) {
      reserve(tx, uid, p.spendMonth, -p.price!, p.currency); // release the reservation
    }
    logActivity(tx, uid, ref.id, done);
    if (order && !order.exists) placeDemoOrder(tx, uid, ref.id, done);
    return done;
  });
}

export function verifyOffer(offerId: unknown, uid: string): OfferClaim {
  try {
    const claim = verify<OfferClaim>(FETCH_SIGNING_KEY.value(), offerId);
    if (claim.kind !== "offer" || claim.uid !== uid) throw new InvalidToken("wrong token");
    if (claim.exp < Date.now()) throw new InvalidToken("offer expired, fetch again");
    return claim;
  } catch (e) {
    if (e instanceof InvalidToken) throw new HttpsError("invalid-argument", `offerId rejected: ${e.message}`);
    throw e;
  }
}

const response = (id: string, p: PurchaseDoc) => ({
  purchaseId: id,
  status: p.status,
  decision: p.decision,
  summary: SUMMARY[p.status],
  reasons: p.reasons,
  title: p.title,
  merchant: p.merchant,
  retailer: p.retailer,
  merchantOfRecord: p.merchantOfRecord,
  retailerOrderStatus: p.retailerOrderStatus,
  imageUrl: p.imageUrl,
  price: p.price,
  currency: p.currency,
  size: p.size,
  undoUntil: p.undoUntil instanceof Timestamp ? p.undoUntil.toMillis() : null,
  paypalOrderId: p.paypalOrderId,
  error: p.error,
});
export type PurchaseResponse = ReturnType<typeof response>;

export const secrets = [CHANNEL3_API_KEY, FETCH_SIGNING_KEY, PAYPAL_CLIENT_ID, PAYPAL_CLIENT_SECRET];
export const requireUid = (uid: string | undefined) => {
  if (!uid) throw new HttpsError("unauthenticated", "Sign in first.");
  return uid;
};

/**
 * The whole purchase path, shared by the app (purchaseOffer) and price watches: live price → rules →
 * record → (auto-buy) undo window → charge. `onDecision` fires as soon as the decision is recorded.
 */
export async function startPurchase(
  uid: string,
  claim: Pick<OfferClaim, "productId" | "merchant" | "color" | "title" | "imageUrl">,
  opts: {
    placeId: string;
    size?: string | null;
    source: "user" | "watch";
    watchId?: string;
    onDecision?: (r: PurchaseResponse) => unknown;
  },
): Promise<PurchaseResponse> {
  const live: LiveOffer = await fetchLiveOffer(clients().c3(uid), claim);
  const ref = userDoc(uid).collection("purchases").doc();

  const p = await db.runTransaction(async (tx) => {
    const ctx = await readContext(tx, uid, opts.placeId);
    const { decision, reasons } = decide({
      rules: ctx.rules,
      monthToDate: ctx.monthToDate,
      offer: live,
      size: opts.size ?? null,
      place: ctx.place ? { id: opts.placeId, label: ctx.place.label } : null,
      knownStores: ctx.knownStores,
      paymentLinked: !!ctx.vaultId,
    });
    const status: PurchaseStatus = decision === "AUTO_BUY" ? "pending_undo" : decision === "NEEDS_APPROVAL" ? "needs_approval" : "blocked";
    const doc: PurchaseDoc = {
      status,
      decision,
      reasons,
      productId: live.productId,
      merchant: live.merchant,
      title: live.title,
      imageUrl: live.imageUrl,
      url: live.url,
      color: claim.color,
      merchantOfRecord: MERCHANT_OF_RECORD,
      retailer: live.merchant,
      retailerOrderStatus: RETAILER_ORDER_STATUS,
      source: opts.source,
      watchId: opts.watchId ?? null,
      size: opts.size ?? null,
      price: live.price,
      currency: live.currency,
      categoryPath: live.categoryPath,
      placeId: opts.placeId,
      placeLabel: ctx.place?.label ?? null,
      shipTo: ctx.place ? toAddress(ctx.place) : null,
      spendMonth: decision === "AUTO_BUY" ? ctx.month : null,
      undoUntil: decision === "AUTO_BUY" ? Timestamp.fromMillis(Date.now() + UNDO_SECONDS * 1000) : null,
      paypalOrderId: null,
      paypalCaptureId: null,
      error: null,
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    };
    tx.set(ref, doc);
    if (decision === "AUTO_BUY") reserve(tx, uid, ctx.month, live.price!, live.currency);
    logActivity(tx, uid, ref.id, doc);
    return doc;
  });

  logger.info("purchase decision", { uid, purchaseId: ref.id, source: opts.source, decision: p.decision, price: p.price });
  await opts.onDecision?.(response(ref.id, p));

  if (p.status === "needs_approval") {
    // TODO(FCM): push "Needs your OK" to the user's devices.
    logger.info("FCM placeholder: Needs your OK", {
      uid,
      purchaseId: ref.id,
      title: `${p.title} · ${money(p.price ?? 0, p.currency)}`,
      body: p.reasons.filter((r) => !r.pass).map((r) => r.message).join(" · "),
    });
    return response(ref.id, p);
  }
  if (p.status !== "pending_undo") return response(ref.id, p);

  await new Promise((r) => setTimeout(r, Math.max(0, p.undoUntil!.toMillis() - Date.now())));
  return response(ref.id, await charge(uid, ref, claimIf("pending_undo")));
}

/**
 * Buy an offer from fetchItem. Streams the decision immediately (so the app can show Undo), then for an
 * auto-buy waits out the undo window and charges; the final result carries the PayPal order id.
 */
export const purchaseOffer = onCall<{ offerId: string; size?: string; placeId: string }, Promise<PurchaseResponse>, PurchaseResponse>(
  { secrets, timeoutSeconds: 60, cors: true },
  async (request, res) => {
    const uid = requireUid(request.auth?.uid);
    const { offerId, size, placeId } = request.data ?? {};
    if (typeof placeId !== "string" || !placeId) throw new HttpsError("invalid-argument", "placeId is required.");
    if (size !== undefined && typeof size !== "string") throw new HttpsError("invalid-argument", "size must be a string.");
    const claim = verifyOffer(offerId, uid);
    return startPurchase(uid, claim, { placeId, size, source: "user", onDecision: (r) => res?.sendChunk(r) });
  },
);

/** After the fingerprint on the device. Re-checks the live price and the hard rules, then charges right away. */
export const approvePurchase = onCall<{ purchaseId: string }, Promise<PurchaseResponse>>(
  { secrets, timeoutSeconds: 60, cors: true },
  async (request) => {
    const uid = requireUid(request.auth?.uid);
    const ref = purchaseRef(uid, request.data?.purchaseId);
    const before = await ref.get();
    if (!before.exists) throw new HttpsError("not-found", "No such purchase.");
    const original = before.data() as PurchaseDoc;
    if (original.status !== "needs_approval") throw new HttpsError("failed-precondition", `This purchase is already ${original.status}.`);

    const live = await fetchLiveOffer(clients().c3(uid), { ...original, color: original.color });

    const p = await db.runTransaction(async (tx) => {
      const cur = (await tx.get(ref)).data() as PurchaseDoc;
      const ctx = await readContext(tx, uid, cur.placeId);
      if (cur.status !== "needs_approval") throw new HttpsError("failed-precondition", `This purchase is already ${cur.status}.`);
      const input = {
        rules: ctx.rules,
        monthToDate: ctx.monthToDate,
        offer: live,
        size: cur.size,
        place: ctx.place ? { id: cur.placeId, label: ctx.place.label } : null,
        knownStores: ctx.knownStores,
        paymentLinked: !!ctx.vaultId,
      };
      const base = { price: live.price, currency: live.currency, title: live.title, imageUrl: live.imageUrl, url: live.url };

      // The user OK'd a specific price. If it went up since, ask again with the new one.
      if (live.price !== null && cur.price !== null && live.price > cur.price + 0.005) {
        const { reasons } = decide(input);
        const update = {
          ...base,
          reasons: [
            { rule: "price_change", pass: false, onFail: "ask" as const, message: `Price went up from ${money(cur.price, cur.currency)} to ${money(live.price, live.currency)}` },
            ...reasons,
          ],
          updatedAt: FieldValue.serverTimestamp(),
        };
        tx.update(ref, update);
        logActivity(tx, uid, ref.id, { ...cur, ...update, status: "needs_approval" });
        return { ...cur, ...update } as PurchaseDoc;
      }

      const { decision, reasons } = decide({ ...input, approved: true });
      const status: PurchaseStatus = decision === "AUTO_BUY" ? "approved" : "blocked";
      const update = {
        ...base,
        status,
        decision,
        reasons,
        placeLabel: ctx.place?.label ?? null,
        shipTo: ctx.place ? toAddress(ctx.place) : null,
        spendMonth: status === "approved" ? ctx.month : null,
        updatedAt: FieldValue.serverTimestamp(),
      };
      tx.update(ref, update);
      if (status === "approved") reserve(tx, uid, ctx.month, live.price!, live.currency);
      logActivity(tx, uid, ref.id, { ...cur, ...update });
      return { ...cur, ...update } as PurchaseDoc;
    });

    if (p.status !== "approved") return response(ref.id, p);
    return response(ref.id, await charge(uid, ref, claimIf("approved")));
  },
);

/** Undo an auto-buy inside its window (nothing is charged), or decline a purchase waiting for approval. */
export const undoPurchase = onCall<{ purchaseId: string }, Promise<PurchaseResponse>>(
  { secrets: [], cors: true },
  async (request) => {
    const uid = requireUid(request.auth?.uid);
    const ref = purchaseRef(uid, request.data?.purchaseId);
    const p = await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      if (!snap.exists) throw new HttpsError("not-found", "No such purchase.");
      const cur = snap.data() as PurchaseDoc;
      let status: PurchaseStatus;
      if (cur.status === "pending_undo") {
        // A little grace for the round trip; the charge itself only starts after this check fails.
        if (Date.now() > cur.undoUntil!.toMillis() + 500) throw new HttpsError("failed-precondition", "Too late to undo: it's already being paid.");
        status = "undone";
        if (cur.spendMonth) reserve(tx, uid, cur.spendMonth, -cur.price!, cur.currency);
      } else if (cur.status === "needs_approval") {
        status = "declined";
      } else {
        throw new HttpsError("failed-precondition", `Can't undo: this purchase is ${cur.status}.`);
      }
      const update = { status, updatedAt: FieldValue.serverTimestamp() };
      tx.update(ref, update);
      logActivity(tx, uid, ref.id, { ...cur, ...update });
      return { ...cur, ...update } as PurchaseDoc;
    });
    return response(ref.id, p);
  },
);

function purchaseRef(uid: string, purchaseId: unknown) {
  if (typeof purchaseId !== "string" || !/^[A-Za-z0-9]{10,40}$/.test(purchaseId)) {
    throw new HttpsError("invalid-argument", "purchaseId is required.");
  }
  return userDoc(uid).collection("purchases").doc(purchaseId);
}

const toAddress = (p: Place): ShippingAddress => ({
  fullName: p.fullName,
  line1: p.line1,
  line2: p.line2 ?? null,
  city: p.city,
  state: p.state,
  postalCode: p.postalCode,
  countryCode: p.countryCode,
});
