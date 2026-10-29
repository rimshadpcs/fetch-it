// Per-user rate limits + the daily circuit breaker, against the emulators. One real fetchItem (lamp: Gemini/Claude +
// 2 Channel3 credits) checks the counting; every other call is refused before any paid API or PayPal call.
// Run with emulators up: node --test test/limits.mjs   (or npm run test:limits)
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { randomBytes } from "node:crypto";
import { test } from "node:test";
import { config } from "dotenv";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import sharp from "sharp";
import { sign } from "../lib/lib/sign.js";

const PROJECT = "fetchit-437a0";
process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8080";
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "127.0.0.1:9090";
config({ path: new URL("../.env.local", import.meta.url).pathname, quiet: true });
const FN = (name) => `http://127.0.0.1:5001/${PROJECT}/us-central1/${name}`;
const UID = "limits-test";

initializeApp({ projectId: PROJECT });
const auth = getAuth();
const db = getFirestore();
const user = db.collection("users").doc(UID);
const limits = db.collection("limits").doc(UID);
const today = new Date().toISOString().slice(0, 10);
const hour = new Date().toISOString().slice(0, 13);
const usage = db.collection("usage").doc(today);

await db.recursiveDelete(user);
await limits.delete();
await usage.delete();
await auth.deleteUser(UID).catch(() => {});
await auth.createUser({ uid: UID });
await user.collection("profile").doc("main").set({ dogName: "Biscuit" });

const { idToken } = await (
  await fetch("http://127.0.0.1:9090/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fake-api-key", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: await auth.createCustomToken(UID), returnSecureToken: true }),
  })
).json();

async function call(name, data) {
  const res = await fetch(FN(name), {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${idToken}` },
    body: JSON.stringify({ data }),
  });
  return res.json();
}
const lamp = (await sharp(readFileSync(new URL("../fixtures/lamp.png", import.meta.url))).resize(1024, 1024, { fit: "inside" }).jpeg().toBuffer()).toString("base64");
const offer = sign(process.env.FETCH_SIGNING_KEY, {
  kind: "offer", uid: UID, productId: "not-fetched", merchant: "wayfair.com", color: null, title: "Lamp", imageUrl: null, exp: Date.now() + 3600_000,
});
const seed = (action, w) => limits.set({ [action]: { hour, hourCount: 0, day: today, dayCount: 0, ...w } });

test("a fetch is counted against the user's limits and today's usage", async () => {
  const { result, error } = await call("fetchItem", { imageBase64: lamp });
  assert.equal(error, undefined, JSON.stringify(error));
  assert.ok(["results", "choose"].includes(result.status));
  const w = (await limits.get()).data().fetchItem;
  assert.deepEqual([w.hourCount, w.dayCount], [1, 1]);
  await new Promise((r) => setTimeout(r, 500)); // usage increments are fire-and-forget
  const u = (await usage.get()).data();
  assert.ok(u.gemini + (u.claude ?? 0) >= 1, JSON.stringify(u));
  assert.ok(u.channel3 >= 1, JSON.stringify(u));
});

test("fetchItem over 30 an hour → quick nap, with the user's dog name", async () => {
  await seed("fetchItem", { hourCount: 30, dayCount: 30 });
  const { error } = await call("fetchItem", { imageBase64: lamp });
  assert.equal(error?.status, "RESOURCE_EXHAUSTED");
  assert.equal(error.message, "Biscuit needs a quick nap, try again in a few minutes.");
  assert.equal((await limits.get()).data().fetchItem.hourCount, 30, "a refused call isn't counted");
});

test("fetchItem over 200 a day → long nap; last hour's count doesn't carry over", async () => {
  await seed("fetchItem", { hour: "2000-01-01T00", hourCount: 30, dayCount: 200 });
  const { error } = await call("fetchItem", { imageBase64: lamp });
  assert.equal(error?.status, "RESOURCE_EXHAUSTED");
  assert.equal(error.message, "Biscuit needs a long nap, try again tomorrow.");
});

test("findSimilar over 30 an hour and purchaseOffer over 20 an hour are refused", async () => {
  await seed("findSimilar", { hourCount: 30 });
  assert.equal((await call("findSimilar", { offerId: offer })).error?.status, "RESOURCE_EXHAUSTED");
  await seed("purchaseOffer", { hourCount: 20 });
  const { error } = await call("purchaseOffer", { offerId: offer, placeId: "home", requestId: randomBytes(12).toString("hex") });
  assert.equal(error?.status, "RESOURCE_EXHAUSTED");
  assert.equal((await user.collection("purchases").get()).size, 0, "no purchase created");
});

test("Channel3 over today's cap → every fetch is refused (circuit breaker)", async () => {
  await limits.delete();
  await usage.set({ channel3: 1_000_000 }, { merge: true });
  const { error } = await call("fetchItem", { imageBase64: lamp });
  assert.equal(error?.status, "RESOURCE_EXHAUSTED");
  assert.equal(error.message, "Biscuit needs a long nap, try again tomorrow.");
  // The first trip per instance and day is logged ("circuit breaker tripped") and marked as usage/{day}.tripped.
  await usage.delete();
});
