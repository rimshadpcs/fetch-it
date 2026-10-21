// End-to-end purchase test against the emulators + PayPal SANDBOX.
// Seeds a test user (house rules, Home address, sandbox vault token) and runs:
//   1. ~$42 lamp → AUTO_BUY → charged after the 5 s undo window
//   2. ~$129 throw → NEEDS_APPROVAL → approvePurchase → charged
//   3. earbuds (electronics) → BLOCKED, nothing charged
//   4. lamp again → AUTO_BUY → undoPurchase inside the window → nothing charged
//   5. tampered / foreign tokens are rejected
//   6. watch the lamp with a target above its price → checkWatchesNow → Price dropped → rules → auto-buy
//   7. findSimilar: "More like this" and "Find cheaper" for the lamp
// Run with emulators up: node test/purchase.mjs   (local emulators only; charges go to the PayPal sandbox)
import { readFile } from "node:fs/promises";
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
const UID = "scout-test";

initializeApp({ projectId: PROJECT });
const auth = getAuth();
const db = getFirestore();

// ---------- seed ----------
const vault = JSON.parse(await readFile(new URL("../../server/fixtures/out/vault.json", import.meta.url), "utf8"));
await db.recursiveDelete(db.collection("users").doc(UID));
await auth.deleteUser(UID).catch(() => {});
await auth.createUser({ uid: UID, displayName: "Scout Test" });
const user = db.collection("users").doc(UID);
await user.set({ dogName: "Scout" });
await user.collection("settings").doc("rules").set({
  currency: "USD",
  monthlyBudget: 300,
  perItemLimit: 75,
  allowedCategories: ["home-garden", "apparel-accessories"],
  askBeforeNewStore: true,
  onlyShipToSavedPlaces: true,
  askIfUnsureAboutSize: true,
});
await user.collection("places").doc("home").set({
  label: "Home",
  isDefault: true,
  fullName: "Fetch Buyer",
  line1: "2211 N First St",
  city: "San Jose",
  state: "CA",
  postalCode: "95131",
  countryCode: "US",
});
await user.collection("stores").doc("wayfair.com").set({ merchant: "wayfair.com", note: "seeded: bought here before" });
await db.collection("vault").doc(UID).set({ paypalVaultId: vault.vault_id, paypalCustomerId: vault.customer_id ?? null });
console.log("seeded users/scout-test: rules ($300/month, $75 per item, home + apparel), Home address, vault token, known store wayfair.com");

const customToken = await auth.createCustomToken(UID);
const { idToken } = await (
  await fetch("http://127.0.0.1:9090/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fake-api-key", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: customToken, returnSecureToken: true }),
  })
).json();

// ---------- helpers ----------
const t0 = performance.now();
const at = () => `${((performance.now() - t0) / 1000).toFixed(1).padStart(5)}s`;
async function call(name, data, onChunk) {
  const res = await fetch(FN(name), {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream", Authorization: `Bearer ${idToken}` },
    body: JSON.stringify({ data }),
  });
  let buf = "";
  for await (const part of res.body) {
    buf += Buffer.from(part).toString();
    let i;
    while ((i = buf.indexOf("\n\n")) >= 0) {
      const line = buf.slice(0, i).replace(/^data: /, "");
      buf = buf.slice(i + 2);
      if (!line.trim()) continue;
      const msg = JSON.parse(line);
      if (msg.error) return { error: msg.error };
      if (msg.message) await onChunk?.(msg.message);
      if (msg.result) return msg.result;
    }
  }
  const text = buf.trim();
  return text ? JSON.parse(text.replace(/^data: /, "")).result : null;
}
const offerToken = (productId, merchant, title) =>
  sign(process.env.FETCH_SIGNING_KEY, { kind: "offer", uid: UID, productId, merchant, color: null, title, imageUrl: null, exp: Date.now() + 3600_000 });
const show = (r) => {
  console.log(`${at()}  → ${r.decision} / ${r.status}: ${r.title} — ${r.price === null ? "no price" : `$${r.price}`} @ ${r.merchant}`);
  for (const x of r.reasons) console.log(`          ${x.pass ? "✓" : x.onFail === "block" ? "✗" : "?"} ${x.message}`);
  if (r.retailer) console.log(`          retailer ${r.retailer} · merchant of record: ${r.merchantOfRecord} · retailer order: ${r.retailerOrderStatus}`);
  if (r.paypalOrderId) console.log(`          PayPal sandbox order ${r.paypalOrderId}`);
  if (r.error) console.log(`          error: ${r.error}`);
};
const orders = [];

// ---------- 1. lamp: auto-buy ----------
console.log(`\n${at()}  1) Lamp ~$42 at wayfair.com`);
const lamp = await call("purchaseOffer", { offerId: offerToken("LtIyk1j", "wayfair.com", "Glass Mushroom Table Lamp"), placeId: "home" }, (c) =>
  console.log(`${at()}  streamed: ${c.decision} / ${c.status}, undo until +${((c.undoUntil - Date.now()) / 1000).toFixed(1)}s`),
);
show(lamp);
if (lamp.paypalOrderId) orders.push(lamp.paypalOrderId);

// ---------- 2. throw: needs approval, then approve ----------
console.log(`\n${at()}  2) Wool throw ~$129 at allmodern.com`);
const throwBlanket = await call("purchaseOffer", { offerId: offerToken("BA51cDw", "allmodern.com", "Barina Wool Throw Blanket"), placeId: "home" });
show(throwBlanket);
console.log(`${at()}  (user approves with fingerprint)`);
const approved = await call("approvePurchase", { purchaseId: throwBlanket.purchaseId });
show(approved);
if (approved.paypalOrderId) orders.push(approved.paypalOrderId);

// ---------- 3. earbuds: blocked ----------
console.log(`\n${at()}  3) Earbuds at bestbuy.com (electronics)`);
show(await call("purchaseOffer", { offerId: offerToken("1xETMWC", "bestbuy.com", "P31i Wireless Earbuds"), placeId: "home" }));

// ---------- 4. lamp again: undo inside the window ----------
console.log(`\n${at()}  4) Lamp again, then Undo after 1 s`);
let undoResult;
const second = await call("purchaseOffer", { offerId: offerToken("LtIyk1j", "wayfair.com", "Glass Mushroom Table Lamp"), placeId: "home" }, async (c) => {
  console.log(`${at()}  streamed: ${c.decision} / ${c.status}`);
  await new Promise((r) => setTimeout(r, 1000));
  undoResult = await call("undoPurchase", { purchaseId: c.purchaseId });
  console.log(`${at()}  undoPurchase → ${undoResult.status}: ${undoResult.summary}`);
});
console.log(`${at()}  purchaseOffer finished with: ${second.status} (PayPal order: ${second.paypalOrderId ?? "none"})`);

// ---------- 5. tokens ----------
console.log(`\n${at()}  5) Token checks`);
const good = offerToken("LtIyk1j", "wayfair.com", "Glass Mushroom Table Lamp");
const [body, sig] = good.split(".");
const tampered = Buffer.from(JSON.stringify({ ...JSON.parse(Buffer.from(body, "base64url")), merchant: "evil.example" })).toString("base64url") + "." + sig;
console.log("   tampered offerId →", (await call("purchaseOffer", { offerId: tampered, placeId: "home" })).error?.message);
const foreign = sign(process.env.FETCH_SIGNING_KEY, { kind: "offer", uid: "someone-else", productId: "LtIyk1j", merchant: "wayfair.com", color: null, title: "x", imageUrl: null, exp: Date.now() + 1e6 });
console.log("   another user's offerId →", (await call("purchaseOffer", { offerId: foreign, placeId: "home" })).error?.message);
console.log("   unsaved placeId →", (await call("purchaseOffer", { offerId: good, placeId: "office" })).reasons?.find((r) => r.rule === "saved_place")?.message);
const tiny = "/9j/4AAQSkZJRgABAQ"; // any bytes: the token is checked before the image is used
console.log("   forged itemsToken →", (await call("fetchItem", { imageBase64: tiny, itemsToken: "e30.AAAA", itemIndex: 0 })).error?.message);

// ---------- 6. price watch ----------
console.log(`\n${at()}  6) Watch the lamp at $50 (it's ~$42 now), then run a check`);
const watch = await call("watchOffer", { offerId: offerToken("LtIyk1j", "wayfair.com", "Glass Mushroom Table Lamp"), targetPrice: 50 });
console.log(`${at()}  watchOffer →`, JSON.stringify(watch));
const check = await call("checkWatchesNow", {});
for (const c of check.results ?? []) {
  console.log(`${at()}  checked "${c.title}": $${c.price} vs target $${c.targetPrice} → ${c.triggered ? "TRIGGERED" : "waiting"}`);
  if (c.purchase) show(c.purchase);
  if (c.purchase?.paypalOrderId) orders.push(c.purchase.paypalOrderId);
}
const w = (await user.collection("watches").doc(watch.watchId).get()).data();
console.log(`          watch now: ${w.status}, purchase ${w.purchaseId} (${w.purchaseStatus}), Channel3 tracking: ${w.channel3Tracking}`);
const again = await call("checkWatchesNow", {});
console.log(`${at()}  second check: ${again.results.length} active watches (triggered watches aren't bought twice)`);

// ---------- 7. find similar ----------
console.log(`\n${at()}  7) findSimilar for the lamp`);
const lampOffer = offerToken("LtIyk1j", "wayfair.com", "Glass Mushroom Table Lamp");
for (const [label, data] of [["More like this", { offerId: lampOffer }], ["Find cheaper", { offerId: lampOffer, maxPrice: 41.99 }]]) {
  const r = await call("findSimilar", data);
  if (r.error) { console.log("   ", label, "error:", r.error.message); continue; }
  console.log(`   ${label}: source $${r.source.price} @ ${r.source.merchant} · category ${r.category} · ${r.offers.length} offers in ${r.timingMs} ms`);
  for (const o of r.offers) console.log(`      $${o.price} ${o.title.slice(0, 55)} @ ${o.merchant} [${o.category}]${o.offerId ? "" : " (no offerId!)"}`);
}

// ---------- results ----------
console.log("\n=== users/scout-test/activity (oldest first) ===");
const acts = await user.collection("activity").orderBy("createdAt").get();
for (const d of acts.docs) {
  const a = d.data();
  console.log(
    `${a.createdAt.toDate().toISOString().slice(11, 23)}  ${(a.type === "watch" ? "watch:" : "") + a.status}`.padEnd(30) + ` ${String(a.title).slice(0, 30).padEnd(30)} $${String(a.price).padEnd(7)} ${a.summary}` +
      `${a.paypalOrderId ? `  [PayPal ${a.paypalOrderId}]` : ""}` +
      `${a.type === "purchase" ? `\n${" ".repeat(14)}retailer ${a.retailer} · ${a.merchantOfRecord} · retailer order ${a.retailerOrderStatus} · source ${a.source}` : ""}` +
      `${a.reasons?.some((r) => !r.pass) ? `\n${" ".repeat(14)}reasons: ${a.reasons.filter((r) => !r.pass).map((r) => r.message).join("; ")}` : ""}` +
      `${a.imageUrl ? `\n${" ".repeat(14)}image: ${a.imageUrl.slice(0, 80)}` : ""}`,
  );
}
const month = new Date().toISOString().slice(0, 7);
console.log(`\nmonth-to-date spend (${month}): $${(await user.collection("spend").doc(month).get()).data()?.totalCents / 100}`);
console.log("known stores:", (await user.collection("stores").get()).docs.map((d) => d.id).join(", "));

// ---------- confirm with PayPal ----------
console.log("\n=== PayPal sandbox orders ===");
const ppAuth = Buffer.from(`${process.env.PAYPAL_CLIENT_ID}:${process.env.PAYPAL_CLIENT_SECRET}`).toString("base64");
const { access_token } = await (
  await fetch(`${process.env.PAYPAL_BASE_URL}/v1/oauth2/token`, { method: "POST", headers: { Authorization: `Basic ${ppAuth}` }, body: new URLSearchParams({ grant_type: "client_credentials" }) })
).json();
for (const id of orders) {
  const o = await (await fetch(`${process.env.PAYPAL_BASE_URL}/v2/checkout/orders/${id}`, { headers: { Authorization: `Bearer ${access_token}` } })).json();
  const pu = o.purchase_units?.[0];
  const a = pu?.shipping?.address;
  console.log(`${id}: ${o.status} ${pu?.amount?.value} ${pu?.amount?.currency_code} · capture ${pu?.payments?.captures?.[0]?.status} · ship to ${pu?.shipping?.name?.full_name}, ${a?.address_line_1}, ${a?.admin_area_2}, ${a?.admin_area_1} ${a?.postal_code}`);
}
process.exit(0);
