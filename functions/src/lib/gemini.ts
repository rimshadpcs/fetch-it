import { GoogleGenAI, MediaResolution, ThinkingLevel, type GenerateContentConfig } from "@google/genai";

export interface IdentifiedItem {
  search_query: string;
  category: string;
  item_type: string;
  colors: string[];
  material: string | null;
  style: string | null;
  likely_brand: string | null;
  confidence: number;
}

const itemSchema = {
  type: "object",
  properties: {
    search_query: { type: "string", description: "Short shopping query (3-8 words) to find this item online" },
    category: { type: "string", description: "Broad shopping category, e.g. 'home lighting', 'apparel'" },
    item_type: { type: "string", description: "Specific item, e.g. 'table lamp', 'quarter-zip fleece pullover'" },
    colors: { type: "array", items: { type: "string" } },
    material: { type: ["string", "null"] },
    style: { type: ["string", "null"] },
    likely_brand: { type: ["string", "null"], description: "Only if a logo or very distinctive design makes it clear" },
    confidence: { type: "number", minimum: 0, maximum: 1 },
  },
  required: ["search_query", "category", "item_type", "colors", "material", "style", "likely_brand", "confidence"],
  additionalProperties: false,
};

const PROMPT =
  "Identify the main product in this photo so a shopper can buy it. Describe what you see; don't invent a brand.";

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

export async function identify(apiKey: string, jpeg: Buffer): Promise<{ item: IdentifiedItem; model: string }> {
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
        responseJsonSchema: itemSchema,
        thinkingConfig: thinking,
        // Half the image tokens of the default with the same answers in testing.
        mediaResolution: MediaResolution.MEDIA_RESOLUTION_MEDIUM,
        abortSignal: AbortSignal.any([ctl.signal, AbortSignal.timeout(TIMEOUT_MS)]),
      },
    });
    return { item: JSON.parse(res.text ?? "") as IdentifiedItem, model };
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
