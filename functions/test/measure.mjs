// Measure the DEPLOYED fetchItem on lamp.png: 1 warm-up (cold start) + 4 warm calls. Run it yourself:
//   cd functions && node test/measure.mjs
// Prompts for the Web API key, the test user's email and password (hidden) unless FIREBASE_WEB_API_KEY,
// TEST_EMAIL, TEST_PASSWORD are set. Uses 10 Channel3 credits. Writes fixtures/out/measure-run.json (gitignored).
import { mkdir, writeFile } from "node:fs/promises";
import { createInterface } from "node:readline/promises";
import sharp from "sharp";

const URL_FN = "https://us-central1-fetchit-437a0.cloudfunctions.net/fetchItem";
const rl = createInterface({ input: process.stdin, output: process.stdout });
const hidden = (prompt) =>
  new Promise((resolve) => {
    process.stdout.write(prompt);
    let v = "";
    process.stdin.setRawMode(true);
    process.stdin.resume();
    const on = (buf) => {
      for (const ch of buf.toString("utf8")) {
        if (ch === "\r" || ch === "\n") {
          process.stdin.setRawMode(false);
          process.stdin.off("data", on);
          process.stdout.write("\n");
          return resolve(v);
        }
        if (ch === "\u0003") process.exit(1);
        v = ch === "\u007f" ? v.slice(0, -1) : v + ch;
      }
    };
    process.stdin.on("data", on);
  });

const apiKey = process.env.FIREBASE_WEB_API_KEY || (await rl.question("Firebase Web API key: ")).trim();
const email = process.env.TEST_EMAIL || (await rl.question("Test user email: ")).trim();
rl.pause();
const password = process.env.TEST_PASSWORD || (await hidden("Password (hidden): "));
rl.close();

const session = await (
  await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${apiKey}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, returnSecureToken: true }),
  })
).json();
if (session.error) throw new Error(`sign-in: ${session.error.message}`);

const jpeg = await sharp(new URL("../fixtures/lamp.png", import.meta.url).pathname)
  .resize({ width: 1024, height: 1024, fit: "inside", withoutEnlargement: true })
  .jpeg({ quality: 80 })
  .toBuffer();
const body = JSON.stringify({ data: { imageBase64: jpeg.toString("base64") } });

const runs = [];
for (let i = 0; i < 5; i++) {
  const t = performance.now();
  const r = await (await fetch(URL_FN, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${session.idToken}` }, body })).json();
  const ms = Math.round(performance.now() - t);
  if (r.error) throw new Error(`fetchItem: ${r.error.message}`);
  const res = r.result;
  const trace = (res.identifyTrace ?? []).map((a) => `${a.role}:${a.model}:${a.ms}ms:${a.outcome}`).join("  ");
  console.log(`${i === 0 ? "warm-up" : `warm #${i}`}: client ${ms} ms · ${res.status} · server ${JSON.stringify(res.timings)}\n   identify → ${res.model}  [${trace}]`);
  runs.push({ label: i === 0 ? "warmup" : `warm${i}`, clientMs: ms, status: res.status, model: res.model, timings: res.timings, identifyTrace: res.identifyTrace, items: res.items.map((x) => [x.item_type, x.prominence, x.confidence]) });
  if (i === 4 && res.offers) {
    console.log("\nTop 5 (last run): final = relevance × priceFactor");
    res.offers.forEach((o, n) =>
      console.log(`  ${n + 1}. ${o.score.final.toFixed(3)} = ${o.score.relevance.toFixed(3)} (img #${o.score.imageRank ?? "-"}, text #${o.score.textRank ?? "-"}) × ${o.score.priceFactor} [median $${o.score.medianPrice}] · $${o.price} ${o.title.slice(0, 44)} @ ${o.merchant}`),
    );
    runs.at(-1).offers = res.offers.map((o) => ({ title: o.title, merchant: o.merchant, price: o.price, score: o.score }));
  }
}
const warm = runs.slice(1);
const p = (k) => warm.map((r) => (k === "client" ? r.clientMs : r.timings[k])).sort((a, b) => a - b);
console.log(`\nwarm identify ms: ${p("identify").join(", ")} · warm fetchItem server total: ${p("total").join(", ")} · client: ${p("client").join(", ")}`);
console.log(`models used: ${warm.map((r) => r.model).join(", ")} · Channel3 credits: ${runs.filter((r) => r.status === "results").length * 2}`);
await mkdir(new URL("../fixtures/out/", import.meta.url), { recursive: true });
await writeFile(new URL("../fixtures/out/measure-run.json", import.meta.url), JSON.stringify({ at: new Date().toISOString(), runs }, null, 2));
console.log("saved fixtures/out/measure-run.json");
process.exit(0);
