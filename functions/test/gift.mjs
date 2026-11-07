// Gift mode end to end: emulators + Channel3 detail + PayPal sandbox (one real sandbox charge, refunded at the end).
// The $42.99 Wayfair lamp as a gift for "Mom": gift budget (not the monthly one), PayPal description + shipping to
// Mom's place, order + activity carry the note, cancel releases the gift spend.
// Run with emulators up: node --test test/gift.mjs
import assert from "node:assert/strict";
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
initializeApp({ projectId: PROJECT });
const db = getFirestore();
const auth = getAuth();
const UID = "gift-test";
const user = db.collection("users").doc(UID);
const month = new Date().toISOString().slice(0, 7);
const vault = JSON.parse(await readFile(new URL("../../server/fixtures/out/vault.json", import.meta.url), "utf8"));

await db.recursiveDelete(user);
await auth.deleteUser(UID).catch(() => {});
await auth.createUser({ uid: UID });
const idToken = (await (await fetch("http://127.0.0.1:9090/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fake-api-key", {
  method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ token: await auth.createCustomToken(UID), returnSecureToken: true }),
})).json()).idToken;
const call = async (name, data) => (await fetch(`http://127.0.0.1:5001/${PROJECT}/us-central1/${name}`, {
  method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${idToken}` }, body: JSON.stringify({ data }),
})).json();

await user.collection("settings").doc("rules").set({
  currency: "USD", monthlyBudget: 300, perItemLimit: 75, allowedCategories: ["home-garden"], askBeforeNewStore: false,
  onlyShipToSavedPlaces: true, askIfUnsureAboutSize: true, giftBudget: 50,
});
await user.collection("places").doc("home").set({ label: "Home", isDefault: true, fullName: "Fetch Buyer", line1: "2211 N First St", city: "San Jose", state: "CA", postalCode: "95131", countryCode: "US" });
await user.collection("places").doc("mom").set({ label: "Mom", fullName: "Jane Doe", line1: "1 Market St", city: "San Francisco", state: "CA", postalCode: "94105", countryCode: "US" });
await db.collection("vault").doc(UID).set({ paypalVaultId: vault.vault_id });
await user.collection("spend").doc(month).set({ totalCents: 29000 }); // $290 of $300 spent: a normal buy would block

const offerId = sign(process.env.FETCH_SIGNING_KEY, { kind: "offer", uid: UID, productId: "LtIyk1j", merchant: "wayfair.com", color: null, title: "Glass Mushroom Table Lamp", imageUrl: null, exp: Date.now() + 3600_000 });
const rid = () => Array.from({ length: 24 }, () => "abcdefghijklmnopqrstuvwxyz0123456789"[Math.floor(Math.random() * 36)]).join("");

test("not a gift: blocked by the nearly-spent monthly budget", async () => {
  const { result, error } = await call("purchaseOffer", { offerId, placeId: "home", requestId: rid() });
  assert.ok(!error, JSON.stringify(error));
  assert.equal(result.status, "blocked");
  assert.ok(result.reasons.some((r) => r.rule === "monthly_budget" && !r.pass));
});

let giftId;
test("gift for Mom: gift budget, charged in the sandbox, shipped to Mom, note on the order", async () => {
  giftId = rid();
  const { result, error } = await call("purchaseOffer", { offerId, placeId: "mom", requestId: giftId, gift: { note: "Happy birthday Mom!" } });
  assert.ok(!error, JSON.stringify(error));
  assert.equal(result.status, "completed", JSON.stringify(result.reasons));
  assert.deepEqual(result.gift, { to: "Mom", note: "Happy birthday Mom!" });
  assert.ok(result.reasons.some((r) => r.rule === "gift_budget" && r.pass));
  const spend = (await user.collection("spend").doc(month).get()).data();
  assert.equal(spend.totalCents, 29000, "monthly spend untouched");
  assert.ok(spend.giftCents > 0);
  const order = (await user.collection("orders").doc(giftId).get()).data();
  assert.equal(order.shipTo.fullName, "Jane Doe");
  assert.equal(order.gift.note, "Happy birthday Mom!");
  const placed = (await user.collection("activity").where("purchaseId", "==", giftId).where("status", "==", "placed").get()).docs[0].data();
  console.log(`   PayPal ${result.paypalOrderId} · ${placed.summary}`);
  assert.match(placed.summary, /^Gift for Mom/);
});

test("a second gift goes over the $50 gift budget → blocked", async () => {
  const { result } = await call("purchaseOffer", { offerId, placeId: "mom", requestId: rid(), gift: {} });
  assert.equal(result.status, "blocked");
  assert.match(result.reasons.find((r) => r.rule === "gift_budget").message, /over your \$50 gift budget/);
});

test("cancelling the gift refunds it and gives the gift budget back", async () => {
  const { error } = await call("cancelOrder", { orderId: giftId });
  assert.ok(!error, JSON.stringify(error));
  const spend = (await user.collection("spend").doc(month).get()).data();
  assert.equal(spend.giftCents, 0);
  assert.equal(spend.totalCents, 29000);
});
