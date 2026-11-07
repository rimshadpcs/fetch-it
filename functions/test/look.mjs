// completeLook on the emulators: real Gemini + Channel3 (3 searches) for the Wayfair lamp. Nothing is bought.
// Run with emulators up: node --test test/look.mjs
import assert from "node:assert/strict";
import { test } from "node:test";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";

const PROJECT = "fetchit-437a0";
process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8080";
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "127.0.0.1:9090";
initializeApp({ projectId: PROJECT });
const db = getFirestore();
const auth = getAuth();
const UID = "look-test";
const user = db.collection("users").doc(UID);
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

await user.collection("purchases").doc("lookbuy01").set({
  status: "completed", productId: "LtIyk1j", merchant: "wayfair.com", title: "Glass Mushroom Table Lamp", color: null,
  categoryPath: ["home-garden", "lighting", "lamps"], price: 42.99, currency: "USD", imageUrl: null,
});

test("suggests things that go with the lamp, each with live-priced, signed offers; second call is cached", async () => {
  const t = Date.now();
  const { result, error } = await call("completeLook", { purchaseId: "lookbuy01" });
  assert.ok(!error, JSON.stringify(error));
  assert.ok(result.groups.length >= 1);
  for (const g of result.groups) {
    assert.ok(g.offers.length >= 1 && g.offers.length <= 2);
    for (const o of g.offers) assert.ok(o.offerId && o.price > 0);
    console.log(`   ${g.label} (${g.why}): ${g.offers.map((o) => `${o.title.slice(0, 40)} $${o.price} @${o.merchant}`).join(" | ")}`);
  }
  console.log(`   ${Date.now() - t} ms`);
  const again = await call("completeLook", { purchaseId: "lookbuy01" });
  assert.equal(again.result.cached, true);
  assert.equal(again.result.groups.length, result.groups.length);
});

test("someone else's / missing purchase → not found", async () => {
  assert.equal((await call("completeLook", { purchaseId: "nosuchbuy1" })).error.status, "NOT_FOUND");
});
