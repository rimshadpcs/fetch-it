// LIVE end-to-end test against the DEPLOYED functions (project fetchit-437a0, PayPal SANDBOX).
// Run it yourself — it signs up / signs in a test user with email + password, which you type in:
//   cd functions && node test/live.mjs
// Prompts for: Web API key (Firebase console → Project settings → General), test email, password (hidden).
// Flow: setRules → startPayPalLink (you approve as the sandbox buyer) → completePayPalLink → confirmPlace →
//       fetchItem(lamp.png) ×2 → purchaseOffer (+approve) → order reaches "confirmed" → second purchase →
//       cancelOrder (refund) → activity. Writes timings to fixtures/out/live-run.json (gitignored).
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { createInterface } from "node:readline/promises";
import sharp from "sharp";

const PROJECT = "fetchit-437a0";
const FN = (name) => `https://us-central1-${PROJECT}.cloudfunctions.net/${name}`;
const FS = `https://firestore.googleapis.com/v1/projects/${PROJECT}/databases/(default)/documents`;
const rl = createInterface({ input: process.stdin, output: process.stdout });

async function hidden(prompt) {
  process.stdout.write(prompt);
  return new Promise((resolve) => {
    let value = "";
    process.stdin.setRawMode(true);
    process.stdin.resume();
    const onData = (buf) => {
      for (const ch of buf.toString("utf8")) {
        if (ch === "\r" || ch === "\n") {
          process.stdin.setRawMode(false);
          process.stdin.off("data", onData);
          process.stdout.write("\n");
          return resolve(value);
        }
        if (ch === "\u0003") process.exit(1);
        value = ch === "\u007f" ? value.slice(0, -1) : value + ch;
      }
    };
    process.stdin.on("data", onData);
  });
}

const apiKey = process.env.FIREBASE_WEB_API_KEY || (await rl.question("Firebase Web API key: ")).trim();
const email = process.env.TEST_EMAIL || (await rl.question("Test user email (new or existing): ")).trim();
rl.pause();
const password = process.env.TEST_PASSWORD || (await hidden("Password (hidden, 6+ chars): "));
rl.resume();

// ---------- auth ----------
async function auth(kind) {
  const r = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:${kind}?key=${apiKey}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, returnSecureToken: true }),
  });
  return r.json();
}
let session = await auth("signUp");
if (session.error?.message === "EMAIL_EXISTS") session = await auth("signInWithPassword");
if (session.error) throw new Error(`auth: ${session.error.message}`);
const { idToken, localId: uid } = session;
console.log(`signed in as ${email} (uid ${uid})`);

// ---------- helpers ----------
const timings = [];
const seen = new Set();
async function call(name, data = {}) {
  const cold = !seen.has(name);
  seen.add(name);
  const t = performance.now();
  const r = await fetch(FN(name), {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${idToken}` },
    body: JSON.stringify({ data }),
  });
  const ms = Math.round(performance.now() - t);
  const json = await r.json().catch(() => ({ error: { message: `HTTP ${r.status}` } }));
  timings.push({ name, ms, firstCall: cold, ok: !json.error });
  console.log(`  ${name.padEnd(20)} ${String(ms).padStart(6)} ms ${cold ? "(first call)" : ""}${json.error ? `  ERROR ${json.error.status}: ${json.error.message}` : ""}`);
  if (json.error) throw new Error(`${name}: ${json.error.message}`);
  return json.result;
}
const val = (v) =>
  v.stringValue ?? v.booleanValue ?? (v.integerValue !== undefined ? Number(v.integerValue) : undefined) ?? v.doubleValue ?? v.timestampValue ??
  (v.nullValue !== undefined ? null : undefined) ?? (v.mapValue ? obj(v.mapValue.fields ?? {}) : undefined) ?? (v.arrayValue ? (v.arrayValue.values ?? []).map(val) : undefined);
const obj = (f) => Object.fromEntries(Object.entries(f).map(([k, v]) => [k, val(v)]));
const readDoc = async (path) => {
  const r = await (await fetch(`${FS}/${path}`, { headers: { Authorization: `Bearer ${idToken}` } })).json();
  return r.fields ? obj(r.fields) : null;
};
const listDocs = async (path) =>
  ((await (await fetch(`${FS}/${path}?pageSize=200&orderBy=createdAt`, { headers: { Authorization: `Bearer ${idToken}` } })).json()).documents ?? []).map((d) => obj(d.fields));
const buyAndSettle = async (offer) => {
  let p = await call("purchaseOffer", { offerId: offer.offerId, placeId: "home" });
  console.log(`    → ${p.decision}: ${p.reasons.filter((r) => !r.pass).map((r) => r.message).join("; ") || "all rules pass"}`);
  if (p.status === "needs_approval") {
    console.log("    (approving, as the fingerprint would)");
    p = await call("approvePurchase", { purchaseId: p.purchaseId });
  }
  console.log(`    → ${p.status} · $${p.price} @ ${p.retailer} · ${p.merchantOfRecord} · PayPal ${p.paypalOrderId}`);
  return p;
};

// ---------- run ----------
console.log("\n1) House rules");
await call("setRules", {
  monthlyBudget: 500, perItemLimit: 100, allowedCategories: ["home-garden", "apparel-accessories"],
  askBeforeNewStore: true, onlyShipToSavedPlaces: true, askIfUnsureAboutSize: true,
});

console.log("\n2) Link PayPal");
const link = await call("startPayPalLink");
console.log(`\n   Open this in a private window, log in as the sandbox buyer and agree:\n   ${link.approvalUrl}\n   (it ends on a spinner because fetchit:// only opens on the phone — that's fine)\n`);
await rl.question("   Press Enter once you've approved… ");
const linked = await call("completePayPalLink", { token: link.setupTokenId });
console.log(`    → linked ${linked.email}, charged: ${linked.charged}, suggested Home: ${linked.suggestedPlace ? `${linked.suggestedPlace.line1}, ${linked.suggestedPlace.city}` : "none"}`);
await call("confirmPlace", { placeId: "home" });

console.log("\n3) fetchItem on lamp.png (twice: first call is cold)");
const jpeg = await sharp(new URL("../fixtures/lamp.png", import.meta.url).pathname)
  .resize({ width: 1024, height: 1024, fit: "inside", withoutEnlargement: true }).jpeg({ quality: 80 }).toBuffer();
const fetched = await call("fetchItem", { imageBase64: jpeg.toString("base64") });
console.log(`    → ${fetched.status} · ${fetched.items.map((i) => i.item_type).join(", ")} · server timings ${JSON.stringify(fetched.timings)}`);
const again = await call("fetchItem", { imageBase64: jpeg.toString("base64") });
console.log(`    → warm: server timings ${JSON.stringify(again.timings)}`);
const offers = fetched.offers ?? [];
offers.forEach((o) => console.log(`    $${o.price} ${o.title.slice(0, 50)} @ ${o.merchant}`));
const pick = [...offers].sort((a, b) => a.price - b.price)[0];
if (!pick) throw new Error("no offers");
console.log(`    buying the cheapest: $${pick.price} ${pick.title} @ ${pick.merchant}`);

console.log("\n4) Buy it, then watch the order");
const first = await buyAndSettle(pick);
const orderPath = `users/${uid}/orders/${first.purchaseId}`;
const t0 = Date.now();
let order = await readDoc(orderPath);
console.log(`    order ${order?.retailerOrderId}: ${order?.status} (promised ${order?.promisedDeliveryDate})`);
while (order && order.status === "placed" && Date.now() - t0 < 4 * 60_000) {
  await new Promise((r) => setTimeout(r, 15_000));
  order = await readDoc(orderPath);
  console.log(`    +${Math.round((Date.now() - t0) / 1000)} s: ${order.status}`);
}

console.log("\n5) Buy again and cancel before it ships");
const second = await buyAndSettle(pick);
const cancelled = await call("cancelOrder", { orderId: second.purchaseId });
console.log(`    → ${cancelled.status} · refund ${cancelled.refund?.id} ${cancelled.refund?.status} $${(cancelled.refund?.amountCents ?? 0) / 100}`);
const month = new Date().toISOString().slice(0, 7);
console.log(`    month-to-date spend: $${((await readDoc(`users/${uid}/spend/${month}`))?.totalCents ?? 0) / 100}`);

console.log("\n6) Activity");
for (const a of await listDocs(`users/${uid}/activity`)) {
  console.log(`    ${String(a.createdAt).slice(11, 19)}  ${(a.type + ":" + a.status).padEnd(26)} ${a.summary}`);
}

const credits = timings.filter((t) => t.name === "fetchItem" && t.ok).length * 2;
console.log(`\nChannel3 credits used: ${credits} (2 per fetchItem: image + text search; product detail is free)`);
console.log("\nLatency (client-measured, includes network):");
for (const t of timings) console.log(`  ${t.name.padEnd(20)} ${String(t.ms).padStart(6)} ms ${t.firstCall ? "first call (cold start likely)" : "warm"}`);
await mkdir(new URL("../fixtures/out/", import.meta.url), { recursive: true });
await writeFile(
  new URL("../fixtures/out/live-run.json", import.meta.url),
  JSON.stringify({ at: new Date().toISOString(), uid, timings, credits, firstPurchase: first.purchaseId, secondPurchase: second.purchaseId, orderStatus: order?.status }, null, 2),
);
console.log("\nsaved fixtures/out/live-run.json");
rl.close();
process.exit(0);
