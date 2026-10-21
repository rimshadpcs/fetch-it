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
  box: Box;
}

const itemSchema = {
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
    "confidence", "box_2d",
  ],
  additionalProperties: false,
};
const responseSchema = {
  type: "object",
  properties: { items: { type: "array", items: itemSchema, maxItems: 6 } },
  required: ["items"],
  additionalProperties: false,
};

const PROMPT = `List every distinct product in this photo that a shopper could want to buy, most prominent first.
- Include clothing, shoes, bags and accessories people are wearing, and the main objects in the scene.
- Use the most specific product type a shop would use (joggers, sweatpants, chinos — not just "trousers").
- Skip tiny or blurry background clutter, walls, floors, and the people themselves.
- For screenshots (shop pages, social posts), list the featured product(s) and ignore app UI, text and icons.
- Describe what you see; don't invent a brand. Give each item a tight box_2d.`;

interface Attempt {
  model: string;
  thinking?: GenerateContentConfig["thinkingConfig"];
}
// Flash-Lite with minimal thinking: ~1.5–2 s typical, but with outliers past 4 s.
const PRIMARY: Attempt = { model: "gemini-3.5-flash-lite", thinking: { thinkingLevel: ThinkingLevel.MINIMAL } };
// When Flash-Lite is overloaded. 3.6 Flash rejects MINIMAL, so it uses its default thinking (~5 s).
// gemini-3.8-flash took 130 s+ per call in Spike 1.
const OVERLOAD_FALLBACK: Attempt = { model: "gemini-3.6-flash" };
/** If the first call hasn't answered by now, send a second one and take whichever finishes first. */
const HEDGE_AFTER_MS = 2500;
const TIMEOUT_MS = 15000;
const OVERLOADED = new Set([429, 500, 503, 504]);

type RawItem = Omit<DetectedItem, "box"> & { box_2d: number[] };

/** Gemini boxes are [ymin, xmin, ymax, xmax] on 0–1000; the app gets 0–1 x/y/width/height. */
function toBox([ymin, xmin, ymax, xmax]: number[]): Box {
  const c = (v: number) => Math.min(1, Math.max(0, v / 1000));
  const [y0, y1] = [c(Math.min(ymin, ymax)), c(Math.max(ymin, ymax))];
  const [x0, x1] = [c(Math.min(xmin, xmax)), c(Math.max(xmin, xmax))];
  return { x: x0, y: y0, width: x1 - x0, height: y1 - y0 };
}

export async function identify(apiKey: string, jpeg: Buffer): Promise<{ items: DetectedItem[]; model: string }> {
  const ai = new GoogleGenAI({ apiKey });
  const data = jpeg.toString("base64");
  const controllers: AbortController[] = [];

  const call = async ({ model, thinking }: Attempt) => {
    const ctl = new AbortController();
    controllers.push(ctl);
    const res = await ai.models.generateContent({
      model,
      contents: [{ inlineData: { mimeType: "image/jpeg", data } }, { text: PROMPT }],
      config: {
        responseMimeType: "application/json",
        responseJsonSchema: responseSchema,
        thinkingConfig: thinking,
        // Half the image tokens of the default with the same answers in testing.
        mediaResolution: MediaResolution.MEDIA_RESOLUTION_MEDIUM,
        abortSignal: AbortSignal.any([ctl.signal, AbortSignal.timeout(TIMEOUT_MS)]),
      },
    });
    const raw = (JSON.parse(res.text ?? "") as { items: RawItem[] }).items ?? [];
    const items = raw
      .filter((i) => Array.isArray(i.box_2d) && i.box_2d.length === 4)
      .map(({ box_2d, ...rest }) => ({ ...rest, box: toBox(box_2d) }))
      .filter((i) => i.box.width > 0.02 && i.box.height > 0.02);
    return { items, model };
  };

  const first = call(PRIMARY);
  // Second call: a hedge after HEDGE_AFTER_MS, or the bigger model straight away if Flash-Lite is overloaded.
  // Only one second call is ever made; if it's already running when the first fails, it decides the outcome.
  const second = new Promise<Awaited<typeof first>>((resolve, reject) => {
    let started = false;
    const start = (attempt: Attempt) => {
      started = true;
      call(attempt).then(resolve, reject);
    };
    const timer = setTimeout(() => start(PRIMARY), HEDGE_AFTER_MS);
    first.then(
      () => clearTimeout(timer),
      (e) => {
        clearTimeout(timer);
        if (started) return;
        const status = (e as { status?: number }).status;
        if (status && OVERLOADED.has(status)) start(OVERLOAD_FALLBACK);
        else reject(e);
      },
    );
  });
  second.catch(() => {}); // a hedge that loses or never starts must not surface as unhandled

  try {
    return await Promise.any([first, second]);
  } catch (e) {
    throw e instanceof AggregateError ? e.errors[0] : e;
  } finally {
    controllers.forEach((c) => c.abort()); // cancel whichever call lost
  }
}
