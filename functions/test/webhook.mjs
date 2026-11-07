// paypalWebhook on the emulators (signature check is skipped only there, with no PAYPAL_WEBHOOK_ID set).
// Seeds an order, then posts PayPal-shaped events. The dispute case calls the PayPal Agent Toolkit (sandbox) + Gemini
// for a dispute that doesn't exist, so it must fall back to the template note. Nothing is charged.
// Run with emulators up: node --test test/webhook.mjs
import assert from "node:assert/strict";
import { test } from "node:test";
import { initializeApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";

const PROJECT = "fetchit-437a0";
process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8080";
initializeApp({ projectId: PROJECT });
const db = getFirestore();
const UID = "webhook-test";
const user = db.collection("users").doc(UID);
const URL_ = `http://127.0.0.1:5001/${PROJECT}/us-central1/paypalWebhook`;
const month = new Date().toISOString().slice(0, 7);

await db.recursiveDelete(user);
await db.collection("vault").doc(UID).set({ paypalVaultId: "VTEST123" });
await user.set({ paypalLinked: true });
await user.collection("spend").doc(month).set({ totalCents: 4199 + 2290 });
const order = (id, captureId, status, totalCents) => ({
  purchaseId: id, status, simulated: true, merchantOfRecord: "Fetch It demo store (PayPal sandbox)", retailer: "wayfair.com",
  retailerOrderId: "DEMO-00001", retailerOrderStatus: "simulated",
  items: [{ productId: "p", title: id === "orderA" ? "Glass Mushroom Lamp" : "Knit Polo", size: null, color: null, imageUrl: null, quantity: 1, priceCents: totalCents }],
  totalCents, currency: "USD", shipTo: null, promisedDeliveryAt: Timestamp.now(), promisedDeliveryDate: "2026-11-11",
  paypalOrderId: `ORD-${id}`, paypalCaptureId: captureId, carrier: null, trackingNumber: null, paypalTracking: null, refund: null,
  spendMonth: month, statusHistory: [], placedAt: Timestamp.now(), updatedAt: Timestamp.now(),
});
await user.collection("orders").doc("orderA").set(order("orderA", "CAPA111", "confirmed", 4199));
await user.collection("purchases").doc("orderA").set({ status: "completed" });
await user.collection("orders").doc("orderB").set(order("orderB", "CAPB222", "cancelling", 2290));
await user.collection("purchases").doc("orderB").set({ status: "completed" });

const post = async (event) => {
  const res = await fetch(URL_, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(event) });
  return { status: res.status, text: await res.text() };
};
const refund = (eventId, refundId, capture, value) => ({
  id: eventId, event_type: "PAYMENT.CAPTURE.REFUNDED",
  resource: { id: refundId, status: "COMPLETED", amount: { value, currency_code: "USD" },
    links: [{ rel: "up", href: `https://api.sandbox.paypal.com/v2/payments/captures/${capture}` }] },
});
const spend = async () => (await user.collection("spend").doc(month).get()).data().totalCents;

test("a refund made in PayPal stops the order, refunds the purchase, rolls back spend, once", async () => {
  const r = await post(refund(`WH-${Date.now()}-a`, "REFA1", "CAPA111", "41.99"));
  assert.equal(r.status, 200, r.text);
  assert.equal(r.text, "refunded, order stopped");
  const o = (await user.collection("orders").doc("orderA").get()).data();
  assert.equal(o.status, "cancelled");
  assert.equal(o.paypalRefunds.length, 1);
  assert.equal((await user.collection("purchases").doc("orderA").get()).data().status, "refunded");
  assert.equal(await spend(), 2290);
  const acts = await user.collection("activity").where("refundId", "==", "REFA1").get();
  assert.equal(acts.size, 1);
  console.log(`   ${acts.docs[0].data().summary}`);
});

test("the same delivery again is a duplicate; the same refund in a new event isn't counted twice", async () => {
  const ev = refund("WH-dup-1", "REFA2", "CAPA111", "1.00");
  assert.equal((await post(ev)).status, 200);
  assert.equal((await post(ev)).text, "duplicate");
  assert.equal((await post({ ...ev, id: "WH-dup-2" })).text, "already recorded");
  assert.equal(await spend(), 2290 - 100);
});

test("our own Cancel order refund (order mid-cancel) is recognised, not double-counted", async () => {
  const before = await spend();
  assert.equal((await post(refund("WH-own-1", "REFB1", "CAPB222", "22.90"))).text, "own refund");
  assert.equal(await spend(), before);
});

test("a dispute is logged on the order; a dispute PayPal can't find falls back to the plain note", async () => {
  const r = await post({
    id: "WH-disp-1", event_type: "CUSTOMER.DISPUTE.CREATED",
    resource: { dispute_id: "PP-D-TEST-0001", reason: "MERCHANDISE_OR_SERVICE_NOT_RECEIVED", status: "OPEN",
      dispute_amount: { value: "22.90", currency_code: "USD" }, disputed_transactions: [{ seller_transaction_id: "CAPB222" }] },
  });
  assert.equal(r.status, 200, r.text);
  const a = (await user.collection("activity").where("disputeId", "==", "PP-D-TEST-0001").get()).docs[0].data();
  assert.equal(a.type, "dispute");
  assert.match(a.summary, /never arrived/);
  assert.equal(a.summarizedBy, undefined);
  console.log(`   ${a.summary}`);
});

test("removing Fetch It in PayPal unlinks it here", async () => {
  assert.equal((await post({ id: "WH-unl-1", event_type: "VAULT.PAYMENT-TOKEN.DELETED", resource: { id: "VTEST123" } })).text, "unlinked 1");
  assert.equal((await db.collection("vault").doc(UID).get()).exists, false);
  assert.equal((await user.get()).data().paypalLinked, false);
});

test("not a POST / not an event → refused; unknown event types are acknowledged", async () => {
  assert.equal((await fetch(URL_)).status, 405);
  assert.equal((await post({ hello: 1 })).status, 400);
  assert.equal((await post({ id: "WH-other", event_type: "BILLING.PLAN.CREATED", resource: {} })).status, 200);
});
