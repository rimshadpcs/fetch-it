// Calls fetchItem in the emulator the way the app will: resize to 1024 px, sign in, stream.
// Run with emulators up:  node test/fetchItem.mjs [fixture.jpg ...]   (local emulator only, throwaway user)
import { readFile } from "node:fs/promises";
import sharp from "sharp";

const PROJECT = "fetchit-437a0";
const AUTH = "http://127.0.0.1:9090/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-api-key";
const FN = `http://127.0.0.1:5001/${PROJECT}/us-central1/fetchItem`;
const files = process.argv.slice(2).length ? process.argv.slice(2) : ["lamp.png"];

const { idToken } = await (
  await fetch(AUTH, { method: "POST", headers: { "Content-Type": "application/json" }, body: '{"returnSecureToken":true}' })
).json();

// Unauthenticated calls must be rejected.
const anon = await fetch(FN, { method: "POST", headers: { "Content-Type": "application/json" }, body: '{"data":{}}' });
console.log("unauthenticated:", anon.status, (await anon.json()).error?.status);

for (const file of files) {
  const jpeg = await sharp(new URL(`../fixtures/${file}`, import.meta.url).pathname)
    .rotate()
    .resize({ width: 1024, height: 1024, fit: "inside", withoutEnlargement: true })
    .jpeg({ quality: 80 })
    .toBuffer();
  console.log(`\n=== ${file} → ${Math.round(jpeg.length / 1024)} KB JPEG ===`);

  const t0 = performance.now();
  const ms = () => `${String(Math.round(performance.now() - t0)).padStart(5)} ms`;
  const res = await fetch(FN, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "text/event-stream", Authorization: `Bearer ${idToken}` },
    body: JSON.stringify({ data: { imageBase64: jpeg.toString("base64") } }),
  });
  if (!res.ok) {
    console.log("HTTP", res.status, await res.text());
    continue;
  }

  // Callable streaming = server-sent events: {"message": chunk} ... then {"result": ...} or {"error": ...}.
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
      if (e?.type === "image_matches") console.log(`${ms()}  image matches: ${e.previews.slice(0, 3).map((p) => p.title).join(" | ")}`);
      if (e?.type === "identified") console.log(`${ms()}  identified (${e.model}): ${JSON.stringify(e.item)}`);
      if (e?.type === "offers") console.log(`${ms()}  offers: ${e.offers.length}`);
    }
  }
  console.log(`${ms()}  done. server timings: ${JSON.stringify(result.timings)}`);
  result.offers.forEach((o, n) => {
    console.log(`\n${n + 1}. ${o.title} — ${o.price} ${o.currency} @ ${o.merchant} (${o.availability}) [${o.matchedBy}] ${o.category}`);
    console.log(`   ${o.url}\n   ${o.imageUrl}`);
    if (o.sizes) console.log(`   sizes: ${o.sizes.map((s) => `${s.label}:${s.available}`).join(", ")}`);
    if (o.colors.length) console.log(`   colors: ${o.colors.map((c) => `${c.label ?? c.hex ?? c.productId}${c.available ? `:${c.available}` : ""}`).join(", ")}`);
  });
}
