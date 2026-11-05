// A category outside the house rules asks first (never blocks), and approving with allowCategory adds it.
// Emulators + live Channel3 (1 search credit) + PayPal SANDBOX (one sandbox charge).
// Run with emulators up: node --test test/category.mjs   (or npm run test:category)
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
const FN = (name) => `http://127.0.0.1:5001/${PROJECT}/us-central1/${name}`;
const UID = "category-test";

initializeApp({ projectId: PROJECT });
const auth = getAuth();
const db = getFirestore();
const user = db.collection("users").doc(UID);
const rulesRef = user.collection("settings").doc("rules");

const vault = JSON.parse(await readFile(new URL("../../server/fixtures/out/vault.json", import.meta.url), "utf8"));
await db.recursiveDelete(user);
await auth.deleteUser(UID).catch(() => {});
await auth.createUser({ uid: UID });
await rulesRef.set({
  currency: "USD", monthlyBudget: 1000, perItemLimit: 500, allowedCategories: ["home-garden"],
  askBeforeNewStore: false, onlyShipToSavedPlaces: true, askIfUnsureAboutSize: false,
});
await user.collection("places").doc("home").set({
  label: "Home", isDefault: true, fullName: "Fetch Buyer", line1: "2211 N First St", city: "San Jose", state: "CA", postalCode: "95131", countryCode: "US",
});
await db.collection("vault").doc(UID).set({ paypalVaultId: vault.vault_id, paypalCustomerId: vault.customer_id ?? null });

const { idToken } = await (
  await fetch("http://127.0.0.1:9090/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fake-api-key", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: await auth.createCustomToken(UID), returnSecureToken: true }),
  })
).json();
async function call(name, data) {
  const res = await fetch(FN(name), { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${idToken}` }, body: JSON.stringify({ data }) });
  const body = await res.json();
  return body.error ? { error: body.error } : body.result;
}

// A real perfume, in stock, under $500.
const search = await (
  await fetch("https://api.trychannel3.com/v1/search", {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-api-key": process.env.CHANNEL3_API_KEY },
    body: JSON.stringify({ query: "eau de parfum perfume", limit: 10 }),
  })
).json();
const perfume = search.products.find((p) => p.category?.path?.[0]?.slug === "health-beauty" && p.offers?.some((o) => o.price?.price < 400));
assert.ok(perfume, "no health-beauty perfume in the search results");
const offer = perfume.offers.find((o) => o.price?.price < 400);
const merchant = offer.domain ?? new URL(offer.url).hostname.replace(/^www\./, "");
const offerId = sign(process.env.FETCH_SIGNING_KEY, { kind: "offer", uid: UID, productId: perfume.id, merchant, color: null, title: perfume.title, imageUrl: null, exp: Date.now() + 3600_000 });

let purchaseId;

test("perfume outside the categories → NEEDS_APPROVAL with only the category failing (was BLOCKED)", async () => {
  const r = await call("purchaseOffer", { offerId, placeId: "home", requestId: crypto.randomUUID().replaceAll("-", "") });
  assert.ok(!r.error, JSON.stringify(r.error));
  console.log(`   ${perfume.title} · $${r.price} @ ${merchant}: ${r.decision}`);
  for (const x of r.reasons.filter((x) => !x.pass)) console.log(`   ? ${x.message}`);
  if (r.reasons.some((x) => x.rule === "in_stock" && !x.pass)) return console.log("   (live detail says not available; skipping)");
  assert.equal(r.decision, "NEEDS_APPROVAL");
  assert.deepEqual(r.reasons.filter((x) => !x.pass).map((x) => x.rule), ["category"]);
  assert.equal(r.reasons.find((x) => x.rule === "category").message, "Health & Beauty isn't in your allowed categories");
  purchaseId = r.purchaseId;
});

test("approve with allowCategory → bought (sandbox) and Health & Beauty added to the rules, logged", async (t) => {
  if (!purchaseId) return t.skip("no purchase to approve");
  const r = await call("approvePurchase", { purchaseId, allowCategory: true });
  assert.ok(!r.error, JSON.stringify(r.error));
  assert.equal(r.status, "completed");
  console.log(`   PayPal sandbox order ${r.paypalOrderId}`);
  assert.deepEqual((await rulesRef.get()).data().allowedCategories.sort(), ["health-beauty", "home-garden"]);
  const log = await user.collection("activity").where("status", "==", "rules_updated").get();
  assert.equal(log.size, 1);
  assert.match(log.docs[0].data().summary, /^Health & Beauty added to your allowed categories/);
});
