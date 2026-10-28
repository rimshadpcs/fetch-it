// setProfile + the size rule reading server-side sizes, against the emulators (live Channel3, no PayPal charge:
// the ~$130 Nordstrom fleece is over the $75 limit, so it can only ask or block).
// Run with emulators up: node --test test/profile.mjs   (or npm run test:profile)
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
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
const UID = "profile-test";

initializeApp({ projectId: PROJECT });
const auth = getAuth();
const db = getFirestore();
const user = db.collection("users").doc(UID);

await db.recursiveDelete(user);
await auth.deleteUser(UID).catch(() => {});
await auth.createUser({ uid: UID });
await user.collection("settings").doc("rules").set({
  currency: "USD", monthlyBudget: 500, perItemLimit: 75, allowedCategories: ["apparel-accessories"],
  askBeforeNewStore: false, onlyShipToSavedPlaces: true, askIfUnsureAboutSize: true,
});
await user.collection("places").doc("home").set({
  label: "Home", fullName: "Fetch Buyer", line1: "2211 N First St", city: "San Jose", state: "CA", postalCode: "95131", countryCode: "US",
});
await db.collection("vault").doc(UID).set({ paypalVaultId: "not-used" });

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
const fleece = sign(process.env.FETCH_SIGNING_KEY, {
  kind: "offer", uid: UID, productId: "7D1m9Uq", merchant: "nordstrom.com", color: null,
  title: "Plaid Print Fleece Quarter-zip Pullover", imageUrl: null, exp: Date.now() + 3600_000,
});
const buy = (size) => call("purchaseOffer", { offerId: fleece, placeId: "home", requestId: randomBytes(12).toString("hex"), ...(size && { size }) });

test("setProfile saves sizes, fit, name and ask-if-unsure, and mirrors ask-if-unsure into the rules", async () => {
  const { result, error } = await call("setProfile", {
    sizes: { top: "S", waist: "32", shoe: "US 9", fit: "Regular" }, askIfUnsure: false, dogName: "Biscuit",
  });
  assert.equal(error, undefined, JSON.stringify(error));
  assert.deepEqual(result.sizes, { top: "S", waist: "32", shoe: "US 9", fit: "Regular" });
  const saved = (await user.collection("profile").doc("main").get()).data();
  assert.equal(saved.dogName, "Biscuit");
  assert.equal(saved.askIfUnsure, false);
  assert.equal((await user.collection("settings").doc("rules").get()).data().askIfUnsureAboutSize, false);

  // Changing one size leaves the others alone.
  await call("setProfile", { sizes: { top: "M" } });
  assert.deepEqual((await user.collection("profile").doc("main").get()).data().sizes, { top: "M", waist: "32", shoe: "US 9", fit: "Regular" });
});

test("setProfile rejects sizes it doesn't know", async () => {
  for (const bad of [{ sizes: { top: "medium-ish" } }, { sizes: { shoe: "9" } }, { sizes: { hat: "M" } }, { dogName: "" }, {}]) {
    const { error } = await call("setProfile", bad);
    assert.equal(error?.status, "INVALID_ARGUMENT", JSON.stringify(bad));
  }
});

test("no size sent: purchaseOffer uses the saved top size, as the store writes it", { timeout: 30_000 }, async () => {
  await call("setProfile", { sizes: { top: "S" } });
  const { result, error } = await buy();
  assert.equal(error, undefined, JSON.stringify(error));
  const size = result.reasons.find((r) => r.rule === "size");
  console.log(`  ${result.decision}: ${size.message} (size recorded: ${result.size})`);
  assert.match(size.message, /^Your size \S+ is (in stock|available|sold out)$/);
  assert.equal(result.size, size.message.split(" ")[2], "the purchase records the store's label");
  assert.notEqual(result.status, "pending_undo", "over the limit: never an auto-buy");
});

test("an explicit size wins over the saved one", { timeout: 30_000 }, async () => {
  const { result } = await buy("Small");
  assert.match(result.reasons.find((r) => r.rule === "size").message, /^Size Small /);
});

test("no saved size for this kind of item and ask-if-unsure off → blocked, 'Pick a size first'", { timeout: 30_000 }, async () => {
  await call("setProfile", { sizes: { top: null }, askIfUnsure: false });
  const { result } = await buy();
  assert.equal(result.decision, "BLOCKED");
  assert.equal(result.reasons.find((r) => r.rule === "size").message, "Pick a size first");
});
