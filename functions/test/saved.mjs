// Saved for later: saveItem / savedOffer. Emulators + live Channel3 product detail (free; no search credits).
// Run with emulators up: node --test test/saved.mjs   (or npm run test:saved)
import assert from "node:assert/strict";
import { test } from "node:test";
import { config } from "dotenv";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { sign, verify } from "../lib/lib/sign.js";

const PROJECT = "fetchit-437a0";
process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8080";
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "127.0.0.1:9090";
config({ path: new URL("../.env.local", import.meta.url).pathname, quiet: true });
const FN = (name) => `http://127.0.0.1:5001/${PROJECT}/us-central1/${name}`;
const UID = "saved-test";

initializeApp({ projectId: PROJECT });
const auth = getAuth();
const db = getFirestore();
const saved = db.collection("users").doc(UID).collection("saved");
await db.recursiveDelete(db.collection("users").doc(UID));
await auth.deleteUser(UID).catch(() => {});
await auth.createUser({ uid: UID });
const { idToken } = await (
  await fetch("http://127.0.0.1:9090/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fake-api-key", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: await auth.createCustomToken(UID), returnSecureToken: true }),
  })
).json();
async function call(name, data) {
  const res = await fetch(FN(name), { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${idToken}` }, body: JSON.stringify({ data }) });
  const body = await res.json();
  return body.error ? { error: body.error } : body.result;
}
const offer = (uid = UID) => sign(process.env.FETCH_SIGNING_KEY, {
  kind: "offer", uid, productId: "LtIyk1j", merchant: "wayfair.com", color: null, title: "Glass Mushroom Table Lamp", imageUrl: null, exp: Date.now() + 3600_000,
});

let id;

test("heart a result: saved once with the live price, saving again is the same entry", async () => {
  const r = await call("saveItem", { offerId: offer(), saved: true });
  assert.ok(!r.error, JSON.stringify(r.error));
  id = r.savedId;
  await call("saveItem", { offerId: offer(), saved: true });
  const all = await saved.get();
  assert.equal(all.size, 1);
  const d = all.docs[0].data();
  console.log(`   saved: ${d.title} · ${d.price === null ? "no live price" : `$${d.price}`} @ ${d.merchant}`);
  assert.equal(d.merchant, "wayfair.com");
});

test("savedOffer re-signs a fresh, verifiable offer for the same product and store", async () => {
  const r = await call("savedOffer", { savedId: id });
  assert.ok(!r.error, JSON.stringify(r.error));
  const claim = verify(process.env.FETCH_SIGNING_KEY, r.offerId);
  assert.deepEqual([claim.kind, claim.uid, claim.productId, claim.merchant], ["offer", UID, "LtIyk1j", "wayfair.com"]);
  console.log(`   live: ${r.inStock ? "in stock" : "not available"} · $${r.price}`);
});

test("someone else's offer can't be saved; un-heart deletes it", async () => {
  assert.ok((await call("saveItem", { offerId: offer("someone-else"), saved: true })).error);
  await call("saveItem", { offerId: offer(), saved: false });
  assert.equal((await saved.get()).size, 0);
  await call("saveItem", { offerId: offer(), saved: true });
  await call("saveItem", { savedId: id, saved: false }); // from the Saved list, by id
  assert.equal((await saved.get()).size, 0);
  assert.equal((await call("savedOffer", { savedId: id })).error?.status, "NOT_FOUND");
});
