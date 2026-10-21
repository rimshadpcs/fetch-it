// Spike 1: photo → Gemini (structured JSON) → Channel3 text + image search → merged top 8.
// Run from functions/:  node spikes/spike1.mjs
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { config } from "dotenv";
import { GoogleGenAI } from "@google/genai";

const here = (p) => fileURLToPath(new URL(p, import.meta.url));
config({ path: here("../.env.local"), quiet: true });

// gemini-3.8-flash is the newest stable Flash, but took 130–230 s per call on our key (Oct 2026);
// gemini-3.6-flash gives the same answers in ~5 s. Override with GEMINI_MODEL to retest.
const MODEL = process.env.GEMINI_MODEL ?? "gemini-3.6-flash";
const C3 = "https://api.trychannel3.com";
const FIXTURES = process.argv.slice(2).length ? process.argv.slice(2) : ["lamp.png"];
const OUT = here("../fixtures/out/");

const ai = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY });

const itemSchema = {
  type: "object",
  properties: {
    category: { type: "string", description: "Broad shopping category, e.g. 'home lighting', 'apparel'" },
    item_type: { type: "string", description: "Specific item, e.g. 'table lamp', 'crew-neck sweater'" },
    colors: { type: "array", items: { type: "string" } },
    material: { type: ["string", "null"] },
    style: { type: ["string", "null"], description: "e.g. 'mid-century modern', 'chunky cable knit'" },
    likely_brand: { type: ["string", "null"], description: "Only if a logo or very distinctive design makes it clear" },
    search_query: { type: "string", description: "Short shopping query (3-8 words) to find this item online" },
    confidence: { type: "number", minimum: 0, maximum: 1 },
  },
  required: ["category", "item_type", "colors", "material", "style", "likely_brand", "search_query", "confidence"],
  additionalProperties: false,
};

// Fixture extensions lie (sweater.png is a JPEG), so sniff the bytes.
function mimeOf(buf) {
  if (buf[0] === 0x89 && buf[1] === 0x50) return "image/png";
  if (buf[0] === 0xff && buf[1] === 0xd8) return "image/jpeg";
  if (buf.subarray(8, 12).toString() === "WEBP") return "image/webp";
  throw new Error("unknown image type");
}

async function timed(label, timings, fn) {
  const t = performance.now();
  try {
    return await fn();
  } finally {
    timings[label] = Math.round(performance.now() - t);
  }
}

const FALLBACK_MODEL = "gemini-3.5-flash-lite";

// Flash models return 503 "high demand" at busy times: retry once, then fall back to Flash-Lite.
async function identify(b64, mimeType) {
  const attempts = [MODEL, MODEL, FALLBACK_MODEL];
  for (const [i, model] of attempts.entries()) {
    try {
      const res = await ai.models.generateContent({
        model,
        contents: [
          { inlineData: { mimeType, data: b64 } },
          { text: "Identify the main product in this photo so a shopper can buy it. Describe what you see; don't invent a brand." },
        ],
        config: { responseMimeType: "application/json", responseJsonSchema: itemSchema },
      });
      return { ...JSON.parse(res.text), _model: model };
    } catch (e) {
      if (![429, 500, 503].includes(e.status) || i === attempts.length - 1) throw e;
      console.log(`  ${model} → HTTP ${e.status}, ${i === 0 ? "retrying" : `falling back to ${FALLBACK_MODEL}`}`);
      if (i === 0) await new Promise((r) => setTimeout(r, 1000));
    }
  }
}

async function channel3(path, body) {
  const res = await fetch(`${C3}${path}`, {
    method: body ? "POST" : "GET",
    headers: { "x-api-key": process.env.CHANNEL3_API_KEY, "Content-Type": "application/json" },
    body: body && JSON.stringify(body),
  });
  const json = await res.json();
  if (!res.ok) throw new Error(`Channel3 ${path} HTTP ${res.status}: ${JSON.stringify(json)}`);
  return json;
}

// Docs show both price.price and price.amount; accept either.
const amount = (o) => o?.price?.price ?? o?.price?.amount ?? null;
const bestOffer = (p) =>
  [...(p.offers ?? [])].sort(
    (a, b) => (a.availability !== "InStock") - (b.availability !== "InStock") || (amount(a) ?? Infinity) - (amount(b) ?? Infinity),
  )[0];
const urlKey = (u) => {
  try {
    const x = new URL(u);
    return x.host + x.pathname.replace(/\/$/, "");
  } catch {
    return u;
  }
};

// Reciprocal-rank fusion over both result lists; items found by both searches rise to the top.
function merge(textProducts, imageProducts) {
  const byKey = new Map();
  const add = (list, source) =>
    list.forEach((p, rank) => {
      const offer = bestOffer(p);
      const keys = [p.id, offer && urlKey(offer.url)].filter(Boolean);
      const hit = keys.map((k) => byKey.get(k)).find(Boolean);
      const entry = hit ?? { product: p, offer, score: 0, sources: new Set() };
      entry.score += 1 / (60 + rank);
      entry.sources.add(source);
      keys.forEach((k) => byKey.set(k, entry));
    });
  add(textProducts, "text");
  add(imageProducts, "image");
  return [...new Set(byKey.values())].sort(
    (a, b) => b.score - a.score || (amount(a.offer) ?? Infinity) - (amount(b.offer) ?? Infinity),
  );
}

function sizeReport(products) {
  const withVariants = products.filter((p) => p.variants?.options?.length);
  const withSize = withVariants.filter((p) => p.variants.options.some((o) => /size/i.test(o.name)));
  return { total: products.length, withVariants: withVariants.length, withSize };
}

const save = (name, data) => writeFile(`${OUT}${name}`, JSON.stringify(data, null, 2) + "\n");

await mkdir(OUT, { recursive: true });
for (const file of FIXTURES) {
  const name = file.replace(/\.\w+$/, "");
  const buf = await readFile(here(`../fixtures/${file}`));
  const b64 = buf.toString("base64");
  const mimeType = mimeOf(buf);
  const timings = {};
  console.log(`\n=== ${file} (${mimeType}, ${Math.round(buf.length / 1024)} KB) ===`);

  // Vision and image search don't depend on each other, so run them together like the app would.
  const [vision, imageSearch] = await Promise.all([
    timed("vision", timings, () => identify(b64, mimeType)),
    timed("image_search", timings, () => channel3("/v1/image-search", { base64_image: b64, limit: 20 })),
  ]);
  const textSearch = await timed("text_search", timings, () =>
    channel3("/v1/search", { query: vision.search_query, limit: 20 }),
  );
  await Promise.all([
    save(`${name}.vision.json`, vision),
    save(`${name}.text-search.json`, textSearch),
    save(`${name}.image-search.json`, imageSearch),
  ]);

  console.log("vision:", JSON.stringify(vision));
  console.log(
    `timing: vision ${timings.vision} ms | image search ${timings.image_search} ms | text search ${timings.text_search} ms`,
  );
  console.log(`results: text ${textSearch.products.length}, image ${imageSearch.products.length}`);

  const merged = merge(textSearch.products, imageSearch.products);
  console.log(`merged: ${merged.length} unique\n`);
  merged.slice(0, 8).forEach(({ product: p, offer, sources }, i) => {
    const img = p.images?.find((im) => im.is_main_image) ?? p.images?.[0];
    console.log(`${i + 1}. ${p.title}  [${[...sources].join("+")}]`);
    console.log(`   ${amount(offer)} ${offer?.price?.currency} @ ${offer?.domain} (${offer?.availability})`);
    console.log(`   url:   ${offer?.url}`);
    console.log(`   image: ${img?.cleaned_url ?? img?.url}`);
  });

  if (name === "sweater") {
    const all = [...textSearch.products, ...imageSearch.products];
    const { total, withVariants, withSize } = sizeReport(all);
    console.log(`\nsizes: ${withVariants}/${total} results have variants, ${withSize.length} have a Size option`);
    // Search never hydrates per-size stock; product detail (free) does.
    const pick = withSize[0];
    if (pick) {
      const detail = await timed("product_detail", timings, () => channel3(`/v1/products/${pick.id}`));
      await save("sweater.product-detail.json", detail);
      const sizeOpt = (v) => v?.options?.find((o) => /size/i.test(o.name));
      console.log(`search  ${pick.id} Size values:`, JSON.stringify(sizeOpt(pick.variants)?.values));
      console.log(`detail  ${pick.id} Size values (${timings.product_detail} ms):`, JSON.stringify(sizeOpt(detail.variants)?.values));
      console.log("detail selected:", JSON.stringify(detail.variants?.selected));
    }
  }
}
