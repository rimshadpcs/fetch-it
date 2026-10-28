// purchaseOffer idempotency against the emulators + PayPal SANDBOX (one real sandbox charge).
// The same Buy tap (same requestId) sent three times — two at once, one after — must create exactly one purchase,
// one PayPal charge and one spend reservation. Also: a requestId reused for another offer, or none at all, is rejected.
// Run with emulators up: node --test test/idempotency.mjs   (or npm run test:idempotency)
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { config } from "dotenv";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { sign } from "../lib/lib/sign.js";

const PROJECT = "fetchit-437a0";
process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8080";
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "127.0.0.1:9090";
config({ path: new URL("../.env.local", import.meta.url).pathname, quiet: true });
const FN = (name) => `http://127.0.0.1:5001/${PROJECT}/us-central1/${name}`;
const UID = "idempotency-test";

initializeApp({ projectId: PROJECT });
const auth = getAuth();
const db = getFirestore();
const user = db.collection("users").doc(UID);

// Same seed as test/purchase.mjs: wayfair.com is a known store, so the ~$42 lamp is an AUTO_BUY.
const vault = JSON.parse(await readFile(new URL("../../server/fixtures/out/vault.json", import.meta.url), "utf8"));
await db.recursiveDelete(user);
await auth.deleteUser(UID).catch(() => {});
await auth.createUser({ uid: UID });
await user.collection("settings").doc("rules").set({
  currency: "USD", monthlyBudget: 300, perItemLimit: 75, allowedCategories: ["home-garden"],
  askBeforeNewStore: true, onlyShipToSavedPlaces: true, askIfUnsureAboutSize: true,
});
await user.collection("places").doc("home").set({
  label: "Home", fullName: "Fetch Buyer", line1: "2211 N First St", city: "San Jose", state: "CA", postalCode: "95131", countryCode: "US",
});
await user.collection("stores").doc("wayfair.com").set({ merchant: "wayfair.com" });
await db.collection("vault").doc(UID).set({ paypalVaultId: vault.vault_id });

const { idToken } = await (
  await fetch("http://127.0.0.1:9090/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fake-api-key", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: await auth.createCustomToken(UID), returnSecureToken: true }),
  })
).json();

/** Streaming callable: resolves with { result } or { error }, plus every streamed chunk. */
async function call(name, data) {
  const res = await fetch(FN(name), {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream", Authorization: `Bearer ${idToken}` },
    body: JSON.stringify({ data }),
  });
  const chunks = [];
  for (const line of (await res.text()).split("\n")) {
    if (!line.startsWith("data:")) continue;
    const msg = JSON.parse(line.slice(5));
    if (msg.message) chunks.push(msg.message);
    if (msg.error) return { error: msg.error, chunks };
    if (msg.result) return { result: msg.result, chunks };
  }
  return { error: { message: `no result (HTTP ${res.status})` }, chunks };
}
const offer = (productId, merchant, title) =>
  sign(process.env.FETCH_SIGNING_KEY, { kind: "offer", uid: UID, productId, merchant, color: null, title, imageUrl: null, exp: Date.now() + 3600_000 });
const lamp = offer("LtIyk1j", "wayfair.com", "Glass Mushroom Table Lamp");
const newRequestId = () => randomBytes(12).toString("hex");

test("the same Buy sent three times creates one purchase and one charge", { timeout: 90_000 }, async () => {
  const requestId = newRequestId();
  const buy = { offerId: lamp, placeId: "home", requestId };

  // Two at once (double tap / transport retry), then one more after the first has finished.
  const [a, b] = await Promise.all([call("purchaseOffer", buy), call("purchaseOffer", buy)]);
  const c = await call("purchaseOffer", buy);
  for (const r of [a, b, c]) {
    assert.equal(r.error, undefined, JSON.stringify(r.error));
    assert.equal(r.result.purchaseId, requestId);
  }

  const purchases = await user.collection("purchases").get();
  assert.equal(purchases.size, 1, "exactly one purchase document");
  const p = purchases.docs[0].data();
  assert.equal(p.status, "completed");
  assert.equal(p.chargeAttempts, 1, "charged once");
  assert.match(p.paypalOrderId, /^[A-Z0-9]{17}$/);
  // Every request, the replays included, ends with the real outcome: the same PayPal order.
  for (const r of [a, b, c]) {
    assert.equal(r.result.status, "completed");
    assert.equal(r.result.paypalOrderId, p.paypalOrderId);
  }

  const month = new Date().toISOString().slice(0, 7);
  const spend = (await user.collection("spend").doc(month).get()).data();
  assert.equal(spend.totalCents, Math.round(p.price * 100), "spend reserved once");

  const activity = await user.collection("activity").where("purchaseId", "==", requestId).get();
  const statuses = activity.docs.map((d) => d.data().status).sort();
  assert.deepEqual(statuses.filter((s) => s === "pending_undo"), ["pending_undo"], "one decision entry");
  assert.deepEqual(statuses.filter((s) => s === "completed"), ["completed"], "one completed entry");
  console.log(`  one purchase ${requestId}: $${p.price} → PayPal ${p.paypalOrderId}; responses ${[a, b, c].map((r) => r.result.status).join(" / ")}`);
});

test("a requestId reused for a different offer is rejected", async () => {
  const requestId = newRequestId();
  const first = await call("purchaseOffer", { offerId: offer("1xETMWC", "bestbuy.com", "P31i Wireless Earbuds"), placeId: "home", requestId });
  assert.equal(first.result?.status, "blocked"); // electronics aren't allowed: nothing charged
  const reused = await call("purchaseOffer", { offerId: lamp, placeId: "home", requestId });
  assert.equal(reused.error?.status, "INVALID_ARGUMENT");
  assert.match(reused.error.message, /different purchase/);
});

test("a Buy without a requestId is rejected", async () => {
  const r = await call("purchaseOffer", { offerId: lamp, placeId: "home" });
  assert.equal(r.error?.status, "INVALID_ARGUMENT");
  assert.match(r.error.message, /requestId/);
});
