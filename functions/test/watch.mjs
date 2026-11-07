// Price watches from the app: watchOffer (one watch per item: watching again changes the target) and stopWatch.
// Emulators + Channel3 product detail (free) + price-tracking start. Nothing is bought or charged.
// Run with emulators up: npm run test:watch
import assert from "node:assert/strict";
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
const UID = "watch-test";
const OTHER = "watch-test-other";

initializeApp({ projectId: PROJECT });
const auth = getAuth();
const db = getFirestore();
const user = db.collection("users").doc(UID);

async function token(uid) {
  await db.recursiveDelete(db.collection("users").doc(uid));
  await auth.deleteUser(uid).catch(() => {});
  await auth.createUser({ uid });
  const r = await fetch("http://127.0.0.1:9090/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fake-api-key", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: await auth.createCustomToken(uid), returnSecureToken: true }),
  });
  return (await r.json()).idToken;
}
const idToken = await token(UID);
const otherToken = await token(OTHER);
await user.collection("places").doc("home").set({ label: "Home", isDefault: true, fullName: "Fetch Buyer", line1: "2211 N First St", city: "San Jose", state: "CA", postalCode: "95131", countryCode: "US" });

const offerId = sign(process.env.FETCH_SIGNING_KEY, {
  kind: "offer", uid: UID, productId: "LtIyk1j", merchant: "wayfair.com", color: null,
  title: "Glass Mushroom Table Lamp", imageUrl: null, exp: Date.now() + 3600_000,
});
async function call(name, data, tok = idToken) {
  const res = await fetch(FN(name), { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${tok}` }, body: JSON.stringify({ data }) });
  return res.json();
}

let watchId;
test("watchOffer creates a watch on the default place, with an activity entry", async () => {
  const { result, error } = await call("watchOffer", { offerId, targetPrice: 30 });
  assert.ok(!error, JSON.stringify(error));
  watchId = result.watchId;
  const w = (await user.collection("watches").doc(watchId).get()).data();
  assert.equal(w.status, "watching");
  assert.equal(w.targetPrice, 30);
  assert.equal(w.placeId, "home");
  console.log(`   watching at $30, now ${result.currentPrice}`);
  const acts = await user.collection("activity").where("watchId", "==", watchId).get();
  assert.equal(acts.size, 1);
});

test("watching the same item again changes the target on the same watch", async () => {
  const before = (await user.collection("watches").doc(watchId).get()).data();
  const { result, error } = await call("watchOffer", { offerId, targetPrice: 35 });
  assert.ok(!error, JSON.stringify(error));
  assert.equal(result.watchId, watchId);
  const all = await user.collection("watches").get();
  assert.equal(all.size, 1);
  const w = all.docs[0].data();
  assert.equal(w.targetPrice, 35);
  assert.equal(w.startPrice, before.startPrice, "startPrice is kept");
});

test("another user can't stop it; bad ids are refused", async () => {
  assert.equal((await call("stopWatch", { watchId }, otherToken)).error?.status, "NOT_FOUND");
  assert.equal((await call("stopWatch", { watchId: "../x" })).error?.status, "INVALID_ARGUMENT");
});

test("stopWatch stops it once, logs it, and refuses a second stop", async () => {
  const { error } = await call("stopWatch", { watchId });
  assert.ok(!error, JSON.stringify(error));
  assert.equal((await user.collection("watches").doc(watchId).get()).data().status, "stopped");
  const acts = await user.collection("activity").where("watchId", "==", watchId).where("status", "==", "stopped").get();
  assert.equal(acts.size, 1);
  assert.equal((await call("stopWatch", { watchId })).error?.status, "FAILED_PRECONDITION");
});

test("after stopping, watching again starts a new watch", async () => {
  const { result, error } = await call("watchOffer", { offerId, targetPrice: 30 });
  assert.ok(!error, JSON.stringify(error));
  assert.notEqual(result.watchId, watchId);
});
