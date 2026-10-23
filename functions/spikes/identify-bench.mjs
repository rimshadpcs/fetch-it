// Benchmark identify backends on every photo in fixtures/: Gemini 3.5 Flash-Lite, Gemini 3 Flash preview, Claude Haiku 5.5.
// Same 1024 px JPEG and the same prompt/schema for all; 1 warm-up (excluded) + 5 interleaved runs per model.
// Saves each run's main-item crop (production crop: box + 10%, ≤768 px) to fixtures/out/crops/<photo>/ plus a contact
// sheet, compares boxes (IoU), and runs one Channel3 image search per model crop to compare top results.
//   cd functions && npm run build && node spikes/identify-bench.mjs [photo ...]
import { readdir, readFile, mkdir, writeFile } from "node:fs/promises";
import { config } from "dotenv";
import sharp from "sharp";
import { geminiOnce } from "../lib/lib/gemini.js";
import { identifyWithClaude } from "../lib/lib/claude.js";
import { prepareImage, cropForSearch } from "../lib/lib/image.js";
import { Channel3 } from "../lib/lib/channel3.js";

config({ path: new URL("../.env.local", import.meta.url).pathname, quiet: true });
const FIX = new URL("../fixtures/", import.meta.url).pathname;
const OUT = new URL("../fixtures/out/crops/", import.meta.url).pathname;
const RUNS = 5;

const MODELS = [
  { id: "gemini-3.5-flash-lite", run: (jpeg) => geminiOnce(process.env.GEMINI_API_KEY, jpeg, "gemini-3.5-flash-lite") },
  { id: "gemini-3-flash-preview", run: (jpeg) => geminiOnce(process.env.GEMINI_API_KEY, jpeg, "gemini-3-flash-preview") },
  { id: "claude-haiku-5-5", run: (jpeg) => identifyWithClaude(process.env.ANTHROPIC_API_KEY, jpeg) },
];

const photos = process.argv.slice(2).length ? process.argv.slice(2) : (await readdir(FIX)).filter((f) => /\.(jpe?g|png|webp)$/i.test(f));
const median = (a) => { const s = [...a].sort((x, y) => x - y); const m = Math.floor(s.length / 2); return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2; };
const iou = (a, b) => {
  const x0 = Math.max(a.x, b.x), y0 = Math.max(a.y, b.y), x1 = Math.min(a.x + a.width, b.x + b.width), y1 = Math.min(a.y + a.height, b.y + b.height);
  const inter = Math.max(0, x1 - x0) * Math.max(0, y1 - y0);
  return inter / (a.width * a.height + b.width * b.height - inter);
};
const medianBox = (boxes) => ({ x: median(boxes.map((b) => b.x)), y: median(boxes.map((b) => b.y)), width: median(boxes.map((b) => b.width)), height: median(boxes.map((b) => b.height)) });
const mainItem = (items) => [...items].sort((a, b) => (b.prominence ?? 0) - (a.prominence ?? 0))[0];
const label = (text, w, h) => Buffer.from(`<svg width="${w}" height="${h}"><rect width="100%" height="100%" fill="#111"/><text x="8" y="${h / 2 + 5}" font-family="Helvetica" font-size="14" fill="#fff">${text}</text></svg>`);

const c3 = new Channel3(process.env.CHANNEL3_API_KEY, "identify-bench");
const report = {};

for (const photo of photos) {
  const img = await prepareImage(await readFile(FIX + photo));
  const dir = `${OUT}${photo.replace(/\.\w+$/, "")}/`;
  await mkdir(dir, { recursive: true });
  console.log(`\n=== ${photo} → ${img.width}×${img.height} JPEG ${Math.round(img.jpeg.length / 1024)} KB`);
  const res = Object.fromEntries(MODELS.map((m) => [m.id, { warmupMs: null, runs: [] }]));

  for (const m of MODELS) {
    try { res[m.id].warmupMs = (await m.run(img.jpeg)).ms; } catch (e) { res[m.id].warmupMs = `ERR ${e.status ?? e.message}`; }
  }
  for (let r = 0; r < RUNS; r++) {
    const order = MODELS.map((_, i) => MODELS[(i + r) % MODELS.length]); // rotate who goes first
    for (const m of order) {
      try {
        const out = await m.run(img.jpeg);
        const main = mainItem(out.items);
        const entry = { ms: out.ms, count: out.items.length, items: out.items.map((i) => `${i.item_type}(p${i.prominence})`), main: main && { type: main.item_type, query: main.search_query, prominence: main.prominence, confidence: main.confidence, box: main.box } };
        res[m.id].runs.push(entry);
        if (main) await sharp(await cropForSearch(img, main.box)).toFile(`${dir}${m.id}-run${r + 1}.jpg`);
      } catch (e) {
        res[m.id].runs.push({ error: `${e.status ?? ""} ${e.message}`.trim().slice(0, 120) });
      }
    }
  }

  // Summaries
  const summary = {};
  for (const m of MODELS) {
    const ok = res[m.id].runs.filter((x) => !x.error);
    const boxes = ok.filter((x) => x.main).map((x) => x.main.box);
    const mbox = boxes.length ? medianBox(boxes) : null;
    summary[m.id] = {
      warmupMs: res[m.id].warmupMs,
      medianMs: ok.length ? median(ok.map((x) => x.ms)) : null,
      worstMs: ok.length ? Math.max(...ok.map((x) => x.ms)) : null,
      errors: res[m.id].runs.length - ok.length,
      itemCounts: ok.map((x) => x.count),
      mainTypes: ok.map((x) => x.main?.type),
      boxStability: mbox ? +median(boxes.map((b) => iou(b, mbox))).toFixed(2) : null, // median IoU of each run vs its own median box
      medianBox: mbox,
    };
  }
  const pairs = [];
  for (let i = 0; i < MODELS.length; i++) for (let j = i + 1; j < MODELS.length; j++) {
    const a = summary[MODELS[i].id].medianBox, b = summary[MODELS[j].id].medianBox;
    pairs.push({ a: MODELS[i].id, b: MODELS[j].id, iou: a && b ? +iou(a, b).toFixed(2) : null });
  }

  // Channel3 image search on each model's median-box crop (1 credit each)
  const searches = {};
  for (const m of MODELS) {
    const box = summary[m.id].medianBox;
    if (!box) continue;
    const crop = await cropForSearch(img, box);
    await sharp(crop).toFile(`${dir}${m.id}-median-crop.jpg`);
    const r = await c3.imageSearch(crop);
    searches[m.id] = r.products.slice(0, 5).map((p) => ({ id: p.id, title: p.title, merchant: p.offers[0]?.domain, price: p.offers[0]?.price?.price }));
  }
  const overlap = [];
  for (let i = 0; i < MODELS.length; i++) for (let j = i + 1; j < MODELS.length; j++) {
    const a = searches[MODELS[i].id] ?? [], b = searches[MODELS[j].id] ?? [];
    const ids = new Set(a.map((x) => x.id));
    overlap.push({ a: MODELS[i].id, b: MODELS[j].id, sharedTop5: b.filter((x) => ids.has(x.id)).length, sameTop1: !!a[0] && a[0].id === b[0]?.id });
  }

  // Contact sheet: one row per model (label + 5 run crops + median crop)
  const T = 180, L = 200;
  const rows = [];
  for (const m of MODELS) {
    const tiles = [{ input: label(m.id, L, T), left: 0, top: 0 }];
    for (let r = 0; r <= RUNS; r++) {
      const f = r < RUNS ? `${dir}${m.id}-run${r + 1}.jpg` : `${dir}${m.id}-median-crop.jpg`;
      try { tiles.push({ input: await sharp(f).resize(T, T, { fit: "contain", background: "#fff" }).toBuffer(), left: L + r * (T + 6), top: 0 }); } catch { /* run failed */ }
    }
    rows.push(await sharp({ create: { width: L + (RUNS + 1) * (T + 6), height: T, channels: 3, background: "#ddd" } }).composite(tiles).png().toBuffer());
  }
  await sharp({ create: { width: L + (RUNS + 1) * (T + 6), height: rows.length * (T + 6), channels: 3, background: "#ddd" } })
    .composite(rows.map((input, i) => ({ input, left: 0, top: i * (T + 6) })))
    .jpeg({ quality: 85 })
    .toFile(`${dir}_contact-sheet.jpg`);

  report[photo] = { summary, boxIoU: pairs, searches, searchOverlap: overlap, runs: res };
  console.log("model".padEnd(24), "warm-up  median  worst  errs  item counts      main item per run");
  for (const m of MODELS) {
    const s = summary[m.id];
    console.log(m.id.padEnd(24), String(s.warmupMs).padStart(7), String(s.medianMs).padStart(7), String(s.worstMs).padStart(6), String(s.errors).padStart(5), " ", JSON.stringify(s.itemCounts).padEnd(16), s.mainTypes.join(" | "), `  [box stability ${s.boxStability}]`);
  }
  console.log("main-box IoU between models:", pairs.map((p) => `${p.a.split("-").slice(0, 3).join("-")} vs ${p.b.split("-").slice(0, 3).join("-")}: ${p.iou}`).join(" · "));
  console.log("Channel3 top-5 overlap:", overlap.map((o) => `${o.a.split("-").slice(0, 3).join("-")} vs ${o.b.split("-").slice(0, 3).join("-")}: ${o.sharedTop5}/5 shared, same #1: ${o.sameTop1}`).join(" · "));
  for (const [id, list] of Object.entries(searches)) console.log(`  ${id}: ${list.map((x) => `${x.title.slice(0, 30)} $${x.price}`).join(" | ")}`);
}
await writeFile(new URL("../fixtures/out/identify-bench.json", import.meta.url), JSON.stringify({ at: new Date().toISOString(), report }, null, 2));
console.log("\nsaved fixtures/out/identify-bench.json and crops under fixtures/out/crops/");
