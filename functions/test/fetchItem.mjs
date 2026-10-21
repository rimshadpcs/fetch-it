// Calls fetchItem in the emulator the way the app will: resize to 1024 px, sign in, stream; on "choose",
// call again per item with itemIndex + the items list. Local emulator only, throwaway user.
//   node test/fetchItem.mjs [fixture ...]     (default: every image in fixtures/)
//   CROPS_DIR=/tmp/x node test/fetchItem.mjs  also writes each item's search crop there for eyeballing
import { readdir } from "node:fs/promises";
import sharp from "sharp";

const PROJECT = "fetchit-437a0";
const AUTH = "http://127.0.0.1:9090/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-api-key";
const FN = `http://127.0.0.1:5001/${PROJECT}/us-central1/fetchItem`;
const FIXTURES = new URL("../fixtures/", import.meta.url).pathname;
const MAX_ITEMS = Number(process.env.MAX_ITEMS ?? 3);

const files = process.argv.slice(2).length
  ? process.argv.slice(2)
  : (await readdir(FIXTURES)).filter((f) => /\.(jpe?g|png|webp|heic)$/i.test(f));

const { idToken } = await (
  await fetch(AUTH, { method: "POST", headers: { "Content-Type": "application/json" }, body: '{"returnSecureToken":true}' })
).json();

const anon = await fetch(FN, { method: "POST", headers: { "Content-Type": "application/json" }, body: '{"data":{}}' });
console.log("unauthenticated call:", anon.status, (await anon.json()).error?.status);

/** One callable request with streaming; prints events as they arrive. */
async function call(data) {
  const t0 = performance.now();
  const ms = () => `${String(Math.round(performance.now() - t0)).padStart(5)} ms`;
  const res = await fetch(FN, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream", Authorization: `Bearer ${idToken}` },
    body: JSON.stringify({ data }),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
  let buf = "";
  let result;
  for await (const part of res.body) {
    buf += Buffer.from(part).toString();
    let i;
    while ((i = buf.indexOf("\n\n")) >= 0) {
      const line = buf.slice(0, i).replace(/^data: /, "");
      buf = buf.slice(i + 2);
      if (!line.trim()) continue;
      const msg = JSON.parse(line);
      if (msg.error) throw new Error(JSON.stringify(msg.error));
      if (msg.result) result = msg.result;
      const e = msg.message;
      if (e?.type === "items") console.log(`${ms()}  items (${e.model}): ${e.items.length}`);
      if (e?.type === "image_matches") console.log(`${ms()}  image matches: ${e.previews.slice(0, 3).map((p) => p.title).join(" | ")}`);
      if (e?.type === "offers") console.log(`${ms()}  offers: ${e.offers.length}`);
    }
  }
  console.log(`${ms()}  ${result.status}. server timings: ${JSON.stringify(result.timings)}`);
  return result;
}

const pct = (n) => `${Math.round(n * 100)}%`;
const printItems = (items) =>
  items.forEach((it, n) =>
    console.log(
      `   [${n}] ${it.item_type} — ${it.colors.join("/")}${it.material ? `, ${it.material}` : ""}` +
        `${it.age ? `, ${it.age}` : ""}${it.likely_brand ? `, brand: ${it.likely_brand}` : ""} (conf ${it.confidence})` +
        `\n       query "${it.search_query}" · box x${pct(it.box.x)} y${pct(it.box.y)} ${pct(it.box.width)}×${pct(it.box.height)}`,
    ),
  );
const printOffers = (offers) =>
  offers.forEach((o, n) => {
    console.log(`   ${n + 1}. ${o.title} — ${o.price} ${o.currency} @ ${o.merchant} [${o.matchedBy}] ${o.category}`);
    if (o.color) console.log(`      colour: ${o.color}`);
    if (o.sizes) console.log(`      sizes: ${o.sizes.map((s) => `${s.label}${s.size && s.size !== s.label ? `=${s.size}` : ""}:${s.available}`).join(", ")}`);
  });

for (const file of files) {
  const jpeg = await sharp(FIXTURES + file)
    .rotate()
    .resize({ width: 1024, height: 1024, fit: "inside", withoutEnlargement: true })
    .jpeg({ quality: 80 })
    .toBuffer();
  const imageBase64 = jpeg.toString("base64");
  console.log(`\n=================== ${file} → ${Math.round(jpeg.length / 1024)} KB JPEG ===================`);

  const first = await call({ imageBase64 });
  printItems(first.items);
  if (process.env.CROPS_DIR) {
    const meta = await sharp(jpeg).metadata();
    for (const [n, it] of first.items.entries()) {
      const left = Math.max(0, Math.floor((it.box.x - it.box.width * 0.1) * meta.width));
      const top = Math.max(0, Math.floor((it.box.y - it.box.height * 0.1) * meta.height));
      const width = Math.min(meta.width - left, Math.ceil(it.box.width * 1.2 * meta.width));
      const height = Math.min(meta.height - top, Math.ceil(it.box.height * 1.2 * meta.height));
      await sharp(jpeg).extract({ left, top, width, height }).toFile(`${process.env.CROPS_DIR}/${file}.${n}.jpg`);
    }
  }

  if (first.status === "results") {
    printOffers(first.offers);
  } else if (first.status === "choose") {
    for (const [n] of first.items.slice(0, MAX_ITEMS).entries()) {
      console.log(`\n -- chose [${n}] ${first.items[n].item_type}`);
      const r = await call({ imageBase64, itemIndex: n, items: first.items });
      printOffers(r.offers);
    }
  }
}
