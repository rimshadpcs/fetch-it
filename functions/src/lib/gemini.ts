import { GoogleGenAI, MediaResolution, ThinkingLevel, type GenerateContentConfig } from "@google/genai";

/** Normalized 0–1 box, origin top-left. */
export interface Box {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface DetectedItem {
  search_query: string;
  category: string;
  item_type: string;
  colors: string[];
  material: string | null;
  style: string | null;
  likely_brand: string | null;
  /** Channel3 age filter values; null when not wearable or unclear. */
  age: "adult" | "kids" | "toddler" | "infant" | "newborn" | null;
  /** Target department when clear from the garment; "unisex"/null are not used as a search filter. */
  gender: "male" | "female" | "unisex" | null;
  confidence: number;
  /** How much this item is the subject of the photo (0 = background clutter, 1 = the obvious subject). */
  prominence: number;
  box: Box;
}

/** Shared by every identify backend (Gemini here, Claude in claude.ts). */
export const itemSchema = {
  type: "object",
  properties: {
    search_query: { type: "string", description: "Short shopping query (3-8 words) to find this item online" },
    category: { type: "string", description: "Broad shopping category, e.g. 'home lighting', 'apparel'" },
    item_type: { type: "string", description: "Specific item, e.g. 'table lamp', 'oversized hoodie'" },
    colors: { type: "array", items: { type: "string" }, description: "Main colour first" },
    material: { type: ["string", "null"] },
    style: { type: ["string", "null"] },
    likely_brand: { type: ["string", "null"], description: "Only if a logo or very distinctive design makes it clear" },
    age: {
      type: ["string", "null"],
      enum: ["adult", "kids", "toddler", "infant", "newborn", null],
      description: "Who it's sized for, for clothing/shoes; null otherwise",
    },
    gender: {
      type: ["string", "null"],
      enum: ["male", "female", "unisex", null],
      description: "Men's or women's department if the cut makes it clear; unisex or null when it doesn't",
    },
    confidence: { type: "number", minimum: 0, maximum: 1 },
    prominence: {
      type: "number",
      minimum: 0,
      maximum: 1,
      description: "How clearly this is the subject the shopper photographed: 1 = the obvious subject, under 0.3 = background clutter",
    },
    box_2d: {
      type: "array",
      items: { type: "integer", minimum: 0, maximum: 1000 },
      minItems: 4,
      maxItems: 4,
      description: "[ymin, xmin, ymax, xmax] normalized to 0-1000",
    },
  },
  required: [
    "search_query", "category", "item_type", "colors", "material", "style", "likely_brand", "age", "gender",
    "confidence", "prominence", "box_2d",
  ],
  additionalProperties: false,
};
export const responseSchema = {
  type: "object",
  properties: { items: { type: "array", items: itemSchema, maxItems: 6 } },
  required: ["items"],
  additionalProperties: false,
};

export const PROMPT = `List every distinct product in this photo that a shopper could want to buy, most prominent first.
- Include clothing, shoes, bags and accessories people are wearing, and the main objects in the scene.
- Use the most specific product type a shop would use (joggers, sweatpants, chinos — not just "trousers").
- Skip tiny or blurry background clutter, walls, floors, and the people themselves.
- For screenshots (shop pages, social posts), list the featured product(s) and ignore app UI, text and icons.
- Describe what you see; don't invent a brand. Give each item a tight box_2d.`;

/** A Gemini model + thinking setting. */
export interface GeminiModel {
  model: string;
  thinking?: GenerateContentConfig["thinkingConfig"];
}
// Oct 23 benchmarks (1024 px JPEG, medium media resolution): 3.5 Flash-Lite 2.0–2.6 s median with 11 s outliers;
// 3 Flash preview hit quota 429s and 15 s timeouts (dropped); 3.6 Flash LOW 1.9–5.7 s; 3.8 Flash 130 s+ (Spike 1).
/** Primary identify model: cheapest, best boxes in the benchmark. */
export const FLASH_LITE: GeminiModel = { model: "gemini-3.5-flash-lite", thinking: { thinkingLevel: ThinkingLevel.MINIMAL } };
/** Strongest model that's still fast; only when the answer we got isn't confident. */
export const STRONG: GeminiModel = { model: "gemini-3.6-flash", thinking: { thinkingLevel: ThinkingLevel.LOW } };
export const TIMEOUT_MS = 15000;


export type RawItem = Omit<DetectedItem, "box"> & { box_2d: number[] };

/** Gemini boxes are [ymin, xmin, ymax, xmax] on 0–1000; the app gets 0–1 x/y/width/height. */
export function toBox([ymin, xmin, ymax, xmax]: number[]): Box {
  const c = (v: number) => Math.min(1, Math.max(0, v / 1000));
  const [y0, y1] = [c(Math.min(ymin, ymax)), c(Math.max(ymin, ymax))];
  const [x0, x1] = [c(Math.min(xmin, xmax)), c(Math.max(xmin, xmax))];
  return { x: x0, y: y0, width: x1 - x0, height: y1 - y0 };
}

/** One Gemini call, for logs: which model, why it ran, how long it took, how it ended. */
export interface AttemptTrace {
  model: string;
  role: "primary" | "race" | "fallback" | "low_confidence";
  startedAtMs: number;
  ms: number | null;
  outcome: "ok" | "lost" | "error";
  status?: number;
  promptTokens?: number;
  outputTokens?: number;
  thoughtsTokens?: number;
}

export interface IdentifyResult {
  items: DetectedItem[];
  /** The model whose answer we used. */
  model: string;
  trace: AttemptTrace[];
}

/** One Gemini call (no retries, no hedging). `signal` lets a race abort it. */
export async function callGemini(apiKey: string, jpeg: Buffer, m: GeminiModel, signal?: AbortSignal) {
  const ai = new GoogleGenAI({ apiKey });
  const res = await ai.models.generateContent({
    model: m.model,
    contents: [{ inlineData: { mimeType: "image/jpeg", data: jpeg.toString("base64") } }, { text: PROMPT }],
    config: {
      responseMimeType: "application/json",
      responseJsonSchema: responseSchema,
      thinkingConfig: m.thinking,
      // Half the image tokens of the default with the same answers in testing.
      mediaResolution: MediaResolution.MEDIA_RESOLUTION_MEDIUM,
      abortSignal: signal ? AbortSignal.any([signal, AbortSignal.timeout(TIMEOUT_MS)]) : AbortSignal.timeout(TIMEOUT_MS),
    },
  });
  return {
    items: toItems((JSON.parse(res.text ?? "") as { items: RawItem[] }).items),
    usage: {
      promptTokens: res.usageMetadata?.promptTokenCount,
      outputTokens: res.usageMetadata?.candidatesTokenCount,
      thoughtsTokens: res.usageMetadata?.thoughtsTokenCount ?? 0,
    },
  };
}

/** Raw model items (0–1000 box_2d) → DetectedItems with 0–1 boxes; drops malformed and sliver boxes. */
export function toItems(raw: RawItem[] | undefined): DetectedItem[] {
  return (raw ?? [])
    .filter((i) => Array.isArray(i.box_2d) && i.box_2d.length === 4)
    .map(({ box_2d, ...rest }) => ({ ...rest, box: toBox(box_2d) }))
    .filter((i) => i.box.width > 0.02 && i.box.height > 0.02);
}

/** One plain call to one Gemini model — for benchmarks. */
export async function geminiOnce(apiKey: string, jpeg: Buffer, model: string): Promise<{ items: DetectedItem[]; ms: number; usage: unknown }> {
  const t = performance.now();
  const r = await callGemini(apiKey, jpeg, { model, thinking: { thinkingLevel: ThinkingLevel.MINIMAL } });
  return { items: r.items, ms: Math.round(performance.now() - t), usage: r.usage };
}
