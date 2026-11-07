// A watched item's purchase carries the price it was when watching started (Activity strikes it through).
// Emulators + Channel3 product detail (free). Nothing is charged: "ask before a new store" stops it at approval.
// Run with emulators up: node --test test/watchprice.mjs
import assert from "node:assert/strict";
import { test } from "node:test";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore, Timestamp } from "firebase-admin/firestore";

const PROJECT = "fetchit-437a0";
process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8080";
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "127.0.0.1:9090";
const FN = (name) => `http://127.0.0.1:5001/${PROJECT}/us-central1/${name}`;
const UID = "watchprice-test";

initializeApp({ projectId: PROJECT });
const auth = getAuth();
const db = getFirestore();
const user = db.collection("users").doc(UID);
await db.recursiveDelete(user);
await auth.deleteUser(UID).catch(() => {});
await auth.createUser({ uid: UID });
await user.collection("settings").doc("rules").set({
  currency: "USD", monthlyBudget: 500, perItemLimit: 100, allowedCategories: ["home-garden"],
  askBeforeNewStore: true, onlyShipToSavedPlaces: true, askIfUnsureAboutSize: false,
});
await user.collection("places").doc("home").set({ label: "Home", isDefault: true, fullName: "Fetch Buyer", line1: "2211 N First St", city: "San Jose", state: "CA", postalCode: "95131", countryCode: "US" });
await db.collection("vault").doc(UID).set({ paypalVaultId: "not-used" });
await user.collection("watches").doc("w1").set({
  status: "watching", productId: "LtIyk1j", merchant: "wayfair.com", color: null, title: "Glass Mushroom Table Lamp", imageUrl: null,
  targetPrice: 50, currency: "USD", size: null, placeId: "home", startPrice: 59.99, lastPrice: 59.99, lastCheckedAt: Timestamp.now(),
  channel3Tracking: "seeded", purchaseId: null, purchaseStatus: null, triggeredAt: null, createdAt: Timestamp.now(), updatedAt: Timestamp.now(),
});
const { idToken } = await (
  await fetch("http://127.0.0.1:9090/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fake-api-key", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: await auth.createCustomToken(UID), returnSecureToken: true }),
  })
).json();

test("price drop → the watch buy records priceWas on the purchase and its activity", async () => {
  const res = await fetch(FN("checkWatchesNow"), { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${idToken}` }, body: JSON.stringify({ data: {} }) });
  const { result, error } = await res.json();
  assert.ok(!error, JSON.stringify(error));
  const c = result.results[0];
  console.log(`   $59.99 → $${c.price} (target $50): ${c.triggered ? c.purchase.status : "not triggered"}`);
  if (!c.triggered) return; // live price above the target today: nothing to check
  assert.equal(c.purchase.status, "needs_approval", "new store → asks, so nothing is charged");
  assert.equal((await user.collection("purchases").doc(c.purchase.purchaseId).get()).data().priceWas, 59.99);
  const entries = await user.collection("activity").where("purchaseId", "==", c.purchase.purchaseId).get();
  assert.ok(entries.size >= 1);
  for (const e of entries.docs) assert.equal(e.data().priceWas, 59.99);
});
