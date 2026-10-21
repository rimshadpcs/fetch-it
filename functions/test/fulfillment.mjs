// Simulated fulfilment, cancel/refund and stuck-purchase cleanup against the emulators + PayPal SANDBOX.
//   A. buy the ~$42 lamp → demo order placed → advanceOrder ×3 (confirmed, shipped + PayPal tracking, delivered)
//   B. buy it again → cancel before shipping → sandbox refund, spend rolled back; cancel after shipping refused
//   C. a purchase stuck in pending_undo → two cleanup runs at once → charged and fulfilled exactly once
// Run with emulators up: node test/fulfillment.mjs   (local emulators only)
import { readFile } from "node:fs/promises";
import { config } from "dotenv";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, getFirestore, Timestamp } from "firebase-admin/firestore";
import { sign } from "../lib/lib/sign.js";

const PROJECT = "fetchit-437a0";
process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8080";
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "127.0.0.1:9090";
config({ path: new URL("../.env.local", import.meta.url).pathname, quiet: true });
const UID = "scout-test";
initializeApp({ projectId: PROJECT });
const auth = getAuth();
const db = getFirestore();
const user = db.collection("users").doc(UID);
const month = new Date().toISOString().slice(0, 7);

// ---------- seed (same house rules as test/purchase.mjs) ----------
const vault = JSON.parse(await readFile(new URL("../../server/fixtures/out/vault.json", import.meta.url), "utf8"));
await db.recursiveDelete(user);
await auth.deleteUser(UID).catch(() => {});
await auth.createUser({ uid: UID });
await user.collection("settings").doc("rules").set({
  currency: "USD", monthlyBudget: 300, perItemLimit: 75, allowedCategories: ["home-garden", "apparel-accessories"],
  askBeforeNewStore: true, onlyShipToSavedPlaces: true, askIfUnsureAboutSize: true,
});
const home = { label: "Home", isDefault: true, fullName: "Fetch Buyer", line1: "2211 N First St", city: "San Jose", state: "CA", postalCode: "95131", countryCode: "US" };
await user.collection("places").doc("home").set(home);
await user.collection("stores").doc("wayfair.com").set({ merchant: "wayfair.com" });
await db.collection("vault").doc(UID).set({ paypalVaultId: vault.vault_id });
const { idToken } = await (
  await fetch("http://127.0.0.1:9090/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fake-api-key", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: await auth.createCustomToken(UID), returnSecureToken: true }),
  })
).json();

const call = async (name, data) => {
  const r = await (await fetch(`http://127.0.0.1:5001/${PROJECT}/us-central1/${name}`, {
    method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${idToken}` }, body: JSON.stringify({ data }),
  })).json();
  return r.result ?? { error: r.error };
};
const lampOffer = () => sign(process.env.FETCH_SIGNING_KEY, {
  kind: "offer", uid: UID, productId: "LtIyk1j", merchant: "wayfair.com", color: null, title: "Glass Mushroom Table Lamp", imageUrl: null, exp: Date.now() + 3600_000,
});
const spend = async () => ((await user.collection("spend").doc(month).get()).data()?.totalCents ?? 0) / 100;
const pp = await (async () => {
  const auth = Buffer.from(`${process.env.PAYPAL_CLIENT_ID}:${process.env.PAYPAL_CLIENT_SECRET}`).toString("base64");
  const { access_token } = await (await fetch(`${process.env.PAYPAL_BASE_URL}/v1/oauth2/token`, { method: "POST", headers: { Authorization: `Basic ${auth}` }, body: new URLSearchParams({ grant_type: "client_credentials" }) })).json();
  return (path) => fetch(`${process.env.PAYPAL_BASE_URL}${path}`, { headers: { Authorization: `Bearer ${access_token}` } }).then((r) => r.json());
})();
const showOrder = (o) =>
  console.log(`   order ${o.orderId}: ${o.status} · ${o.retailerOrderId} at ${o.retailer} · simulated=${o.simulated} · ${o.merchantOfRecord}` +
    `${o.trackingNumber ? ` · ${o.carrier.name} ${o.trackingNumber} · PayPal tracking ${o.paypalTracking?.status}${o.paypalTracking?.error ? ` (${o.paypalTracking.error})` : ""}` : ""}` +
    `${o.refund ? ` · refund ${o.refund.id} ${o.refund.status} $${o.refund.amountCents / 100}` : ""}`);

// ---------- A ----------
console.log("A) Buy the lamp, then fast-forward its demo order");
const a = await call("purchaseOffer", { offerId: lampOffer(), placeId: "home" });
console.log(`   purchase ${a.purchaseId}: ${a.status} · PayPal ${a.paypalOrderId}`);
const placed = (await user.collection("orders").doc(a.purchaseId).get()).data();
console.log(`   order created: ${placed.status} ${placed.retailerOrderId} · simulated ${placed.simulated} · $${placed.totalCents / 100} · ship to ${placed.shipTo.label} · promised ${placed.promisedDeliveryDate} · PayPal ${placed.paypalOrderId} / capture ${placed.paypalCaptureId}`);
for (let i = 0; i < 3; i++) showOrder(await call("advanceOrder", { orderId: a.purchaseId }));
const ppA = await pp(`/v2/checkout/orders/${a.paypalOrderId}`);
const trackers = ppA.purchase_units?.[0]?.shipping?.trackers ?? [];
console.log(`   PayPal sandbox order ${a.paypalOrderId} trackers:`, JSON.stringify(trackers.map((t) => ({ id: t.id, status: t.status, items: t.items?.length }))));
const finalA = (await user.collection("orders").doc(a.purchaseId).get()).data();
console.log("   statusHistory:", finalA.statusHistory.map((h) => h.status).join(" → "));
console.log("   cancel after delivery →", (await call("cancelOrder", { orderId: a.purchaseId })).error?.message);

// ---------- B ----------
console.log("\nB) Buy again, cancel before shipping");
const b = await call("purchaseOffer", { offerId: lampOffer(), placeId: "home" });
console.log(`   purchase ${b.purchaseId}: ${b.status} · PayPal ${b.paypalOrderId} · month-to-date $${await spend()}`);
showOrder(await call("advanceOrder", { orderId: b.purchaseId })); // confirmed
const cancelled = await call("cancelOrder", { orderId: b.purchaseId });
showOrder(cancelled);
console.log(`   month-to-date after cancel: $${await spend()}`);
const capB = (await user.collection("orders").doc(b.purchaseId).get()).data().paypalCaptureId;
const capture = await pp(`/v2/payments/captures/${capB}`);
console.log(`   PayPal capture ${capB}: ${capture.status}`);
if (cancelled.refund) {
  const refund = await pp(`/v2/payments/refunds/${cancelled.refund.id}`);
  console.log(`   PayPal refund ${refund.id}: ${refund.status} ${refund.amount?.value} ${refund.amount?.currency_code}`);
}
console.log("   cancel again →", (await call("cancelOrder", { orderId: b.purchaseId })).error?.message);
console.log("   purchase now:", (await user.collection("purchases").doc(b.purchaseId).get()).data().status);

// ---------- C ----------
console.log("\nC) A purchase stuck in pending_undo (its request died), two cleanup runs at once");
const stuckRef = user.collection("purchases").doc();
const old = Timestamp.fromMillis(Date.now() - 3 * 60 * 1000);
await db.runTransaction(async (tx) => {
  tx.set(stuckRef, {
    status: "pending_undo", decision: "AUTO_BUY", reasons: [], productId: "LtIyk1j", merchant: "wayfair.com",
    title: "Glass Mushroom Table Lamp", imageUrl: null, url: null, color: null, size: null, price: 41.99, currency: "USD",
    categoryPath: ["home-garden", "lighting", "lamps", "bedside-lamps"], placeId: "home", placeLabel: "Home",
    shipTo: { fullName: home.fullName, line1: home.line1, line2: null, city: home.city, state: home.state, postalCode: home.postalCode, countryCode: "US" },
    merchantOfRecord: "Fetch It demo store (PayPal sandbox)", retailer: "wayfair.com", retailerOrderStatus: "simulated",
    source: "user", watchId: null, spendMonth: month, undoUntil: Timestamp.fromMillis(old.toMillis() + 5000),
    paypalOrderId: null, paypalCaptureId: null, error: null, createdAt: old, updatedAt: old,
  });
  tx.set(user.collection("spend").doc(month), { totalCents: FieldValue.increment(4199) }, { merge: true });
});
console.log(`   seeded stuck purchase ${stuckRef.id} (undo window ended 3 min ago) · month-to-date $${await spend()}`);
const runs = await Promise.all([call("runMaintenance", {}), call("runMaintenance", {})]);
runs.forEach((r, i) => console.log(`   cleanup run ${i + 1}:`, JSON.stringify(r.stuck ?? r.error)));
const third = await call("runMaintenance", {});
console.log("   cleanup run 3 (later):", JSON.stringify(third.stuck));
const s = (await stuckRef.get()).data();
console.log(`   purchase: ${s.status} · recoveredBy ${s.recoveredBy} · charge attempts ${s.chargeAttempts} · PayPal ${s.paypalOrderId}`);
const ordersForStuck = await user.collection("orders").where("purchaseId", "==", stuckRef.id).get();
const actsForStuck = await user.collection("activity").where("purchaseId", "==", stuckRef.id).get();
console.log(`   orders for it: ${ordersForStuck.size} · activity entries: ${actsForStuck.docs.map((d) => `${d.data().type}:${d.data().status}${d.data().recoveredBy ? "(cleanup)" : ""}`).join(", ")}`);
const ppS = await pp(`/v2/checkout/orders/${s.paypalOrderId}`);
console.log(`   PayPal ${s.paypalOrderId}: ${ppS.status} ${ppS.purchase_units?.[0]?.amount?.value} USD, reference_id ${ppS.purchase_units?.[0]?.reference_id} (= purchase id: ${ppS.purchase_units?.[0]?.reference_id === stuckRef.id})`);
console.log(`   month-to-date: $${await spend()}`);

console.log("\n=== activity (oldest first) ===");
for (const d of (await user.collection("activity").orderBy("createdAt").get()).docs) {
  const x = d.data();
  console.log(`${x.createdAt.toDate().toISOString().slice(11, 19)}  ${(x.type + ":" + x.status).padEnd(26)} ${x.summary}`);
}
process.exit(0);
