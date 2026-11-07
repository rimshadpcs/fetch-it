// PayPal → us. Keeps Activity true to what happened in PayPal, even when it happened outside the app:
//   refund / reversal made in PayPal   → order + purchase marked, month-to-date spend rolled back, activity entry
//   dispute opened / updated / closed  → activity entry (on open, Scout reads it with the PayPal Agent Toolkit)
//   Fetch It removed in PayPal settings → vault token dropped, paypalLinked false: nothing is bought until relinked
//   capture completed / denied         → recorded on the order (denied also logs)
// Every delivery is verified with PayPal (verify-webhook-signature) and handled once (paypalEvents/{eventId}).
// Our own refunds (Cancel order) come back here too and are recognised, not counted twice.

import { onRequest } from "firebase-functions/v2/https";
import { defineString } from "firebase-functions/params";
import { logger } from "firebase-functions";
import { FieldValue, Timestamp, type DocumentReference, type DocumentSnapshot } from "firebase-admin/firestore";
import { GEMINI_API_KEY, PAYPAL_CLIENT_ID, PAYPAL_CLIENT_SECRET } from "../config.js";
import { db, userDoc } from "../lib/db.js";
import { money } from "../rules.js";
import { clients } from "../purchase/purchase.js";
import type { OrderDoc } from "../fulfillment.js";
import { notify } from "../push.js";
import { disputeSummary, parseEvent, type Parsed, type PayPalEvent } from "./events.js";
import { summarizeDispute } from "./disputeAgent.js";

/** The id PayPal gave our listener when it was registered (npm run webhook:register). Empty = refuse everything. */
export const PAYPAL_WEBHOOK_ID = defineString("PAYPAL_WEBHOOK_ID", { default: "" });

const cents = (n: number) => Math.round(n * 100);

async function orderByCapture(captureId: string): Promise<{ uid: string; snap: DocumentSnapshot } | null> {
  const q = await db.collectionGroup("orders").where("paypalCaptureId", "==", captureId).limit(1).get();
  if (q.empty) return null;
  return { uid: q.docs[0].ref.parent.parent!.id, snap: q.docs[0] };
}

async function dogName(uid: string) {
  const name = (await userDoc(uid).collection("profile").doc("main").get()).data()?.dogName;
  return typeof name === "string" && name ? name : "Scout";
}

function activity(uid: string, entry: Record<string, unknown>) {
  return userDoc(uid).collection("activity").doc().set({ source: "paypal_webhook", createdAt: FieldValue.serverTimestamp(), ...entry });
}

const orderFields = (orderId: string, o: OrderDoc) => ({
  orderId,
  purchaseId: o.purchaseId,
  title: o.items[0]?.title ?? null,
  imageUrl: o.items[0]?.imageUrl ?? null,
  retailer: o.retailer,
  retailerOrderId: o.retailerOrderId,
  merchantOfRecord: o.merchantOfRecord,
  simulated: true,
  paypalOrderId: o.paypalOrderId,
  currency: o.currency,
});

async function onRefund(e: Extract<Parsed, { kind: "refund" }>): Promise<string> {
  const found = await orderByCapture(e.captureId);
  if (!found) return "no order for capture";
  const { uid, snap } = found;
  const ref = snap.ref as DocumentReference;
  const amountCents = cents(e.amount);
  const result = await db.runTransaction(async (tx) => {
    const o = (await tx.get(ref)).data() as OrderDoc & { paypalRefunds?: { id: string }[] };
    // Ours: Cancel order is mid-refund, or already recorded this refund id.
    if (o.status === "cancelling" || o.refund?.id === e.refundId) return "own refund";
    if ((o.paypalRefunds ?? []).some((r) => r.id === e.refundId)) return "already recorded";
    const refundedBefore = (o.paypalRefunds ?? []).reduce((s, r) => s + ((r as { amountCents?: number }).amountCents ?? 0), 0) + (o.refund?.amountCents ?? 0);
    const full = refundedBefore + amountCents >= o.totalCents;
    const at = Timestamp.now();
    // Not shipped yet: a full refund stops the (simulated) store from shipping it.
    const stop = full && (o.status === "placed" || o.status === "confirmed");
    tx.update(ref, {
      paypalRefunds: FieldValue.arrayUnion({ id: e.refundId, amountCents, reversed: e.reversed, at }),
      ...(stop && { status: "cancelled", refund: { id: e.refundId, status: "COMPLETED", amountCents }, statusHistory: FieldValue.arrayUnion({ status: "cancelled", at }) }),
      updatedAt: FieldValue.serverTimestamp(),
    });
    if (full) tx.update(userDoc(uid).collection("purchases").doc(o.purchaseId), { status: "refunded", updatedAt: FieldValue.serverTimestamp() });
    if (o.spendMonth) {
      tx.set(userDoc(uid).collection("spend").doc(o.spendMonth), { totalCents: FieldValue.increment(-amountCents), updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    }
    const amount = money(e.amount, e.currency);
    tx.set(userDoc(uid).collection("activity").doc(), {
      type: "order",
      source: "paypal_webhook",
      status: stop ? "cancelled" : "refunded",
      summary: e.reversed
        ? `PayPal reversed the ${amount} payment for ${o.items[0]?.title ?? "this order"}.`
        : `${amount} refunded to your PayPal${full ? "" : " (part of the order)"}. The refund was made in PayPal${stop ? ", so the order won't ship" : ""}.`,
      ...orderFields(ref.id, o),
      price: e.amount,
      refundId: e.refundId,
      createdAt: FieldValue.serverTimestamp(),
    });
    return stop ? "refunded, order stopped" : "refunded";
  });
  return result;
}

async function onDispute(e: Extract<Parsed, { kind: "dispute" }>): Promise<string> {
  let found: Awaited<ReturnType<typeof orderByCapture>> = null;
  for (const id of e.captureIds) if ((found = await orderByCapture(id))) break;
  if (!found) return "no order for disputed capture";
  const { uid, snap } = found;
  const o = snap.data() as OrderDoc;
  const title = o.items[0]?.title ?? "your order";
  let summary = disputeSummary(e, title, money);
  let agent: string[] | null = null;
  if (e.stage === "created") {
    const r = await summarizeDispute(
      { gemini: GEMINI_API_KEY.value(), paypalClientId: PAYPAL_CLIENT_ID.value(), paypalClientSecret: PAYPAL_CLIENT_SECRET.value() },
      { disputeId: e.disputeId, title, storeName: o.retailer, dogName: await dogName(uid) },
    );
    if (r) {
      summary = r.summary;
      agent = r.toolCalls;
    }
  }
  await snap.ref.update({
    dispute: { id: e.disputeId, stage: e.stage, reason: e.reason, status: e.status, outcome: e.outcome },
    updatedAt: FieldValue.serverTimestamp(),
  });
  await activity(uid, {
    type: "dispute",
    status: `dispute_${e.stage}`,
    summary,
    ...orderFields(snap.id, o),
    price: e.amount,
    disputeId: e.disputeId,
    disputeReason: e.reason,
    ...(agent && { summarizedBy: "PayPal Agent Toolkit + Gemini", agentToolCalls: agent }),
  });
  if (e.stage !== "updated") await notify(uid, { title: e.stage === "created" ? `Dispute opened: ${title}` : `Dispute closed: ${title}`, body: summary, open: "purchase", purchaseId: o.purchaseId });
  return `dispute ${e.stage}${agent ? " (agent)" : ""}`;
}

async function onUnlinked(e: Extract<Parsed, { kind: "unlinked" }>): Promise<string> {
  const q = await db.collection("vault").where("paypalVaultId", "==", e.vaultId).limit(5).get();
  if (q.empty) return "unknown vault token";
  for (const d of q.docs) {
    const uid = d.id;
    await db.runTransaction(async (tx) => {
      tx.delete(d.ref);
      tx.set(userDoc(uid), { paypalLinked: false, paypalUnlinkedAt: FieldValue.serverTimestamp() }, { merge: true });
    });
    const dog = await dogName(uid);
    await activity(uid, {
      type: "paypal",
      status: "unlinked",
      summary: `You removed Fetch It from your PayPal account. ${dog} won't buy anything until you link PayPal again.`,
    });
  }
  return `unlinked ${q.size}`;
}

async function onCapture(e: Extract<Parsed, { kind: "capture" }>): Promise<string> {
  const found = await orderByCapture(e.captureId);
  if (!found) return "no order for capture";
  await found.snap.ref.update({ paypalCaptureStatus: e.status.toUpperCase(), updatedAt: FieldValue.serverTimestamp() });
  if (e.status === "denied") {
    const o = found.snap.data() as OrderDoc;
    await activity(found.uid, { type: "order", status: "payment_denied", summary: `PayPal denied the payment for ${o.items[0]?.title ?? "this order"}.`, ...orderFields(found.snap.id, o) });
  }
  return `capture ${e.status}`;
}

export async function handleEvent(event: PayPalEvent): Promise<string> {
  const parsed = parseEvent(event);
  switch (parsed.kind) {
    case "refund":
      return onRefund(parsed);
    case "dispute":
      return onDispute(parsed);
    case "unlinked":
      return onUnlinked(parsed);
    case "capture":
      return onCapture(parsed);
    default:
      return parsed.why;
  }
}

export const paypalWebhook = onRequest(
  { secrets: [PAYPAL_CLIENT_ID, PAYPAL_CLIENT_SECRET, GEMINI_API_KEY], timeoutSeconds: 60, memory: "512MiB" },
  async (req, res) => {
    if (req.method !== "POST") {
      res.status(405).send("POST only");
      return;
    }
    let event: PayPalEvent;
    try {
      event = JSON.parse(req.rawBody.toString("utf8"));
    } catch {
      res.status(400).send("bad json");
      return;
    }
    if (typeof event?.id !== "string" || typeof event.event_type !== "string") {
      res.status(400).send("not a PayPal event");
      return;
    }

    // Verify with PayPal. Only the local emulator skips it (PayPal can't reach it, and the test posts its own events).
    const webhookId = PAYPAL_WEBHOOK_ID.value();
    const emulatorTest = process.env.FUNCTIONS_EMULATOR === "true";
    if (!emulatorTest) {
      const headers = Object.fromEntries(
        ["paypal-auth-algo", "paypal-cert-url", "paypal-transmission-id", "paypal-transmission-sig", "paypal-transmission-time"].map((k) => [k, req.get(k)]),
      );
      const ok = webhookId ? await clients().paypal().verifyWebhook(headers, webhookId, event).catch((e) => {
        logger.error("webhook verify call failed", { eventId: event.id, message: (e as Error).message });
        return false;
      }) : false;
      if (!ok) {
        logger.warn("webhook rejected", { eventId: event.id, type: event.event_type, configured: !!webhookId });
        res.status(401).send("unverified");
        return;
      }
    }

    // Once per event id: PayPal retries until it gets a 2xx.
    const marker = db.collection("paypalEvents").doc(event.id.replace(/[^A-Za-z0-9-]/g, "_"));
    try {
      await marker.create({ type: event.event_type, receivedAt: FieldValue.serverTimestamp() });
    } catch {
      logger.info("webhook duplicate", { eventId: event.id, type: event.event_type });
      res.status(200).send("duplicate");
      return;
    }
    try {
      const outcome = await handleEvent(event);
      await marker.update({ outcome });
      logger.info("webhook handled", { eventId: event.id, type: event.event_type, outcome });
      res.status(200).send(outcome);
    } catch (e) {
      await marker.delete().catch(() => {});
      logger.error("webhook failed", { eventId: event.id, type: event.event_type, message: (e as Error).message });
      res.status(500).send("retry");
    }
  },
);
