// SIMULATED store fulfilment for the hackathon demo. No order is placed with the real retailer: the money
// goes to our PayPal sandbox merchant, and this module plays the store (confirm → ship → deliver) on a timer.
// Everything it writes says so: simulated: true, retailerOrderStatus: "simulated", DEMO-… order numbers,
// "Fetch It Demo Post" tracking.

import { randomInt } from "node:crypto";
import { ENFORCE_APP_CHECK } from "./options.js";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { defineInt } from "firebase-functions/params";
import { logger } from "firebase-functions";
import { FieldValue, Timestamp, type DocumentReference, type Transaction } from "firebase-admin/firestore";
import { PAYPAL_CLIENT_ID, PAYPAL_CLIENT_SECRET } from "./config.js";
import { db, userDoc } from "./lib/db.js";
import type { ShippingAddress } from "./lib/paypal.js";
import { money } from "./rules.js";
import { PayPalError } from "./lib/paypal.js";
import { paypalFailure } from "./lib/paypalErrors.js";
import { clients, MERCHANT_OF_RECORD, RETAILER_ORDER_STATUS, requireUid, spendField, type PurchaseDoc } from "./purchase/purchase.js";

// Timings, overridable per environment (functions/.env or .env.local).
const CONFIRM_AFTER_SECONDS = defineInt("FULFILLMENT_CONFIRM_AFTER_SECONDS", { default: 30 });
const SHIP_AFTER_SECONDS = defineInt("FULFILLMENT_SHIP_AFTER_SECONDS", { default: 120 });
const DELIVERY_DAYS = defineInt("FULFILLMENT_DELIVERY_DAYS", { default: 3 });

export const DEMO_CARRIER = { code: "OTHER", name: "Fetch It Demo Post" } as const;

export type OrderStatus = "placed" | "confirmed" | "shipped" | "delivered" | "cancelling" | "cancelled";

export interface OrderDoc {
  purchaseId: string;
  status: OrderStatus;
  simulated: true;
  merchantOfRecord: string;
  retailer: string;
  retailerOrderId: string;
  retailerOrderStatus: typeof RETAILER_ORDER_STATUS;
  items: { productId: string; title: string; size: string | null; color: string | null; imageUrl: string | null; quantity: number; priceCents: number }[];
  totalCents: number;
  currency: string;
  shipTo: (ShippingAddress & { placeId: string; label: string | null }) | null;
  promisedDeliveryAt: Timestamp;
  /** YYYY-MM-DD, for "Arrives Friday". */
  promisedDeliveryDate: string;
  paypalOrderId: string | null;
  paypalCaptureId: string | null;
  carrier: { code: string; name: string } | null;
  trackingNumber: string | null;
  /** Tracking pushed to the PayPal sandbox transaction (Orders v2 /track). */
  paypalTracking: { status: "pending" | "added" | "failed"; trackerId?: string | null; error?: string | null } | null;
  refund: { id: string; status: string; amountCents: number } | null;
  gift?: { to: string; note: string | null } | null;
  /** Spend month this order counts against, so a cancel can roll it back. */
  spendMonth: string | null;
  statusHistory: { status: OrderStatus; at: Timestamp }[];
  placedAt: Timestamp;
  updatedAt: FieldValue | Timestamp;
}

const SUMMARY: Record<OrderStatus, (o: OrderDoc) => string> = {
  placed: (o) =>
    `${o.gift ? `Gift for ${o.gift.to}: order` : "Order"} ${o.retailerOrderId} placed with ${o.retailer} (demo store · sandbox). Arrives ${weekday(o.promisedDeliveryDate)}.` +
    (o.gift?.note ? ` Note: "${o.gift.note}"` : ""),
  confirmed: (o) => `${o.retailer} confirmed order ${o.retailerOrderId} (simulated).`,
  shipped: (o) => `Shipped with ${o.carrier?.name}, tracking ${o.trackingNumber} (simulated).`,
  delivered: (o) => (o.gift ? `Delivered to ${o.gift.to} (simulated).` : `Delivered to ${o.shipTo?.label ?? "your place"} (simulated).`),
  cancelling: () => "Cancelling…",
  cancelled: (o) => `Cancelled. ${money((o.refund?.amountCents ?? o.totalCents) / 100, o.currency)} refunded to your PayPal.`,
};

const weekday = (isoDate: string) => new Date(`${isoDate}T12:00:00Z`).toLocaleDateString("en-US", { weekday: "long", timeZone: "UTC" });
const cents = (n: number) => Math.round(n * 100);
const orderRef = (uid: string, orderId: string) => userDoc(uid).collection("orders").doc(orderId);

function orderActivity(tx: Transaction, uid: string, orderId: string, o: OrderDoc, extra: Record<string, unknown> = {}) {
  tx.set(userDoc(uid).collection("activity").doc(), {
    type: "order",
    orderId,
    purchaseId: o.purchaseId,
    status: o.status,
    summary: SUMMARY[o.status](o),
    title: o.items[0]?.title ?? null,
    gift: o.gift ?? null,
    retailer: o.retailer,
    retailerOrderId: o.retailerOrderId,
    merchantOfRecord: o.merchantOfRecord,
    retailerOrderStatus: o.retailerOrderStatus,
    simulated: true,
    price: o.totalCents / 100,
    currency: o.currency,
    imageUrl: o.items[0]?.imageUrl ?? null,
    trackingNumber: o.trackingNumber,
    carrier: o.carrier?.name ?? null,
    promisedDeliveryDate: o.promisedDeliveryDate,
    paypalOrderId: o.paypalOrderId,
    createdAt: FieldValue.serverTimestamp(),
    ...extra,
  });
}

/**
 * Called inside the transaction that marks a purchase completed, so the order exists exactly once per
 * successful capture. The order id is the purchase id. Caller must have read `orderRef` first (exists=false).
 */
export function placeDemoOrder(tx: Transaction, uid: string, purchaseId: string, p: PurchaseDoc) {
  const now = Timestamp.now();
  const promised = new Date(now.toMillis() + DELIVERY_DAYS.value() * 86_400_000);
  const order: OrderDoc = {
    purchaseId,
    status: "placed",
    simulated: true,
    merchantOfRecord: MERCHANT_OF_RECORD,
    retailer: p.retailer ?? p.merchant,
    retailerOrderId: `DEMO-${String(randomInt(0, 100_000)).padStart(5, "0")}`,
    retailerOrderStatus: RETAILER_ORDER_STATUS,
    items: [
      { productId: p.productId, title: p.title, size: p.size, color: p.color?.label ?? null, imageUrl: p.imageUrl, quantity: 1, priceCents: cents(p.price!) },
    ],
    totalCents: cents(p.price!),
    currency: p.currency,
    shipTo: p.shipTo ? { ...p.shipTo, placeId: p.placeId, label: p.placeLabel } : null,
    promisedDeliveryAt: Timestamp.fromDate(promised),
    promisedDeliveryDate: promised.toISOString().slice(0, 10),
    paypalOrderId: p.paypalOrderId,
    paypalCaptureId: p.paypalCaptureId,
    carrier: null,
    trackingNumber: null,
    paypalTracking: null,
    refund: null,
    gift: p.gift ?? null,
    spendMonth: p.spendMonth,
    statusHistory: [{ status: "placed", at: now }],
    placedAt: now,
    updatedAt: FieldValue.serverTimestamp(),
  };
  tx.create(orderRef(uid, purchaseId), order);
  orderActivity(tx, uid, purchaseId, order);
}

/** The next status this order is due for, or null. `force` skips the wait (debug fast-forward). */
function nextStatus(o: OrderDoc, now: number, force: boolean): OrderStatus | null {
  const age = (now - o.placedAt.toMillis()) / 1000;
  switch (o.status) {
    case "placed":
      return force || age >= CONFIRM_AFTER_SECONDS.value() ? "confirmed" : null;
    case "confirmed":
      return force || age >= SHIP_AFTER_SECONDS.value() ? "shipped" : null;
    case "shipped":
      return force || now >= o.promisedDeliveryAt.toMillis() ? "delivered" : null;
    default:
      return null;
  }
}

/** Advance one step if due (or forced). The status check and the write are one transaction. */
async function advance(uid: string, ref: DocumentReference, force: boolean): Promise<OrderDoc | null> {
  const advanced = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) throw new HttpsError("not-found", "No such order.");
    const o = snap.data() as OrderDoc;
    const next = nextStatus(o, Date.now(), force);
    if (!next) return null;
    const at = Timestamp.now();
    const update: Partial<OrderDoc> = { status: next };
    if (next === "shipped") {
      update.carrier = { ...DEMO_CARRIER };
      update.trackingNumber = `DEMO${String(randomInt(0, 1e9)).padStart(9, "0")}${String(randomInt(0, 1e6)).padStart(6, "0")}`;
      update.paypalTracking = { status: "pending" };
    }
    tx.update(ref, { ...update, statusHistory: FieldValue.arrayUnion({ status: next, at }), updatedAt: FieldValue.serverTimestamp() });
    const after = { ...o, ...update, statusHistory: [...o.statusHistory, { status: next, at }] } as OrderDoc;
    orderActivity(tx, uid, ref.id, after);
    return after;
  });
  if (advanced?.status === "shipped") return syncTracking(uid, ref, advanced);
  return advanced;
}

/** Push carrier + tracking number to the PayPal sandbox capture. Retried by the sweep until it sticks. */
async function syncTracking(uid: string, ref: DocumentReference, o: OrderDoc): Promise<OrderDoc> {
  if (!o.paypalOrderId || !o.paypalCaptureId || !o.trackingNumber) return o;
  let tracking: OrderDoc["paypalTracking"];
  try {
    const res = await clients()
      .paypal()
      .addTracking(o.paypalOrderId, {
        captureId: o.paypalCaptureId,
        trackingNumber: o.trackingNumber,
        carrier: o.carrier?.code ?? DEMO_CARRIER.code,
        carrierNameOther: o.carrier?.name ?? DEMO_CARRIER.name,
        notifyPayer: false,
        requestId: `track-${ref.id}`,
      });
    const tracker = res.purchase_units?.[0]?.shipping?.trackers?.find((t) => t.id.includes(o.trackingNumber!)) ?? res.purchase_units?.[0]?.shipping?.trackers?.[0];
    tracking = { status: "added", trackerId: tracker?.id ?? null, error: null };
  } catch (e) {
    logger.error("PayPal add tracking failed", { uid, orderId: ref.id, ...(e instanceof PayPalError ? e.toLog() : { message: (e as Error).message }) });
    tracking = { status: "failed", error: (e as Error).message };
  }
  await ref.update({ paypalTracking: tracking, updatedAt: FieldValue.serverTimestamp() });
  return { ...o, paypalTracking: tracking };
}

/** Advance every open order that's due, and retry PayPal tracking that didn't stick. */
export async function runOrderSweep(): Promise<{ advanced: number; trackingRetried: number }> {
  const snap = await db.collectionGroup("orders").where("status", "in", ["placed", "confirmed", "shipped"]).get();
  let advanced = 0;
  let trackingRetried = 0;
  for (const d of snap.docs) {
    const uid = d.ref.parent.parent!.id;
    const o = d.data() as OrderDoc;
    try {
      if (o.status === "shipped" && o.paypalTracking?.status !== "added") {
        trackingRetried++;
        await syncTracking(uid, d.ref, o);
      }
      if (await advance(uid, d.ref, false)) advanced++;
    } catch (e) {
      logger.error("order sweep failed", { path: d.ref.path, message: (e as Error).message });
    }
  }
  return { advanced, trackingRetried };
}

const fulfilmentSecrets = [PAYPAL_CLIENT_ID, PAYPAL_CLIENT_SECRET];

export const advanceOrders = onSchedule({ schedule: "every 1 minutes", secrets: fulfilmentSecrets, timeoutSeconds: 120 }, async () => {
  logger.info("order sweep", await runOrderSweep());
});

/** Debug-only callables: on in the emulator, or when ALLOW_DEBUG=true is set for a filming build. */
export function requireDebug() {
  if (process.env.FUNCTIONS_EMULATOR !== "true" && process.env.ALLOW_DEBUG !== "true") {
    throw new HttpsError("permission-denied", "Debug functions are disabled.");
  }
}

/** Fast-forward one of your orders to its next status (for filming). */
export const advanceOrder = onCall<{ orderId: string }>({ secrets: fulfilmentSecrets, cors: true, enforceAppCheck: ENFORCE_APP_CHECK }, async (request) => {
  requireDebug();
  const uid = requireUid(request.auth?.uid);
  const ref = orderRef(uid, validId(request.data?.orderId));
  const o = await advance(uid, ref, true);
  return o ? publicOrder(ref.id, o) : publicOrder(ref.id, (await ref.get()).data() as OrderDoc);
});

/** Cancel before it ships: refund the sandbox capture, mark cancelled, roll back month-to-date spend. */
export const cancelOrder = onCall<{ orderId: string }>({ secrets: fulfilmentSecrets, cors: true, enforceAppCheck: ENFORCE_APP_CHECK, timeoutSeconds: 60 }, async (request) => {
  const uid = requireUid(request.auth?.uid);
  const ref = orderRef(uid, validId(request.data?.orderId));

  // Claim it first, so the sweep can't ship it while the refund is in flight.
  const before = await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) throw new HttpsError("not-found", "No such order.");
    const o = snap.data() as OrderDoc;
    if (o.status !== "placed" && o.status !== "confirmed") {
      throw new HttpsError("failed-precondition", `Too late to cancel: this order is ${o.status}.`);
    }
    tx.update(ref, { status: "cancelling", updatedAt: FieldValue.serverTimestamp() });
    return o;
  });

  try {
    if (!before.paypalCaptureId) throw new Error("No PayPal capture to refund");
    const refund = await clients()
      .paypal()
      .refundCapture(before.paypalCaptureId, { requestId: `refund-${ref.id}`, note: `Fetch It order ${before.retailerOrderId} cancelled` });
    const amountCents = refund.amount ? cents(Number(refund.amount.value)) : before.totalCents;
    const after = await db.runTransaction(async (tx) => {
      const at = Timestamp.now();
      const o: OrderDoc = {
        ...before,
        status: "cancelled",
        refund: { id: refund.id, status: refund.status, amountCents },
        statusHistory: [...before.statusHistory, { status: "cancelled", at }],
      };
      tx.update(ref, {
        status: o.status,
        refund: o.refund,
        statusHistory: FieldValue.arrayUnion({ status: "cancelled", at }),
        updatedAt: FieldValue.serverTimestamp(),
      });
      tx.update(userDoc(uid).collection("purchases").doc(before.purchaseId), { status: "refunded", updatedAt: FieldValue.serverTimestamp() });
      if (before.spendMonth) {
        tx.set(
          userDoc(uid).collection("spend").doc(before.spendMonth),
          { [spendField(before.gift)]: FieldValue.increment(-amountCents), updatedAt: FieldValue.serverTimestamp() },
          { merge: true },
        );
      }
      orderActivity(tx, uid, ref.id, o, { refundId: refund.id });
      return o;
    });
    return publicOrder(ref.id, after);
  } catch (e) {
    // Put it back so it can be retried; the refund call is idempotent per order.
    await db.runTransaction(async (tx) => {
      tx.update(ref, { status: before.status, updatedAt: FieldValue.serverTimestamp() });
      tx.set(userDoc(uid).collection("activity").doc(), {
        type: "order",
        orderId: ref.id,
        purchaseId: before.purchaseId, // shows on the order's card in the app
        status: "cancel_failed",
        summary: `Couldn't cancel order ${before.retailerOrderId}: ${(e as Error).message}`,
        simulated: true,
        createdAt: FieldValue.serverTimestamp(),
      });
    });
    const mapped = paypalFailure(e, "cancelOrder", { uid, orderId: ref.id });
    if (mapped instanceof HttpsError) throw mapped;
    logger.error("cancelOrder refund failed", { uid, orderId: ref.id, message: (e as Error).message });
    throw new HttpsError("unavailable", "Couldn't cancel right now. Try again.");
  }
});

function validId(id: unknown): string {
  if (typeof id !== "string" || !/^[A-Za-z0-9]{10,40}$/.test(id)) throw new HttpsError("invalid-argument", "orderId is required.");
  return id;
}

const publicOrder = (id: string, o: OrderDoc) => ({
  orderId: id,
  status: o.status,
  simulated: o.simulated,
  retailer: o.retailer,
  retailerOrderId: o.retailerOrderId,
  merchantOfRecord: o.merchantOfRecord,
  totalCents: o.totalCents,
  currency: o.currency,
  promisedDeliveryDate: o.promisedDeliveryDate,
  carrier: o.carrier,
  trackingNumber: o.trackingNumber,
  paypalTracking: o.paypalTracking,
  refund: o.refund,
  gift: o.gift ?? null,
  statusHistory: o.statusHistory.map((h) => ({ status: h.status, at: h.at.toMillis() })),
});
