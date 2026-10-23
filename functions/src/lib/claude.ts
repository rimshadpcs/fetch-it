// Claude identify backend (benchmark candidate). Same prompt, schema and output as Gemini identify in gemini.ts,
// so the pipeline can swap or race them. Strict tool use gives schema-valid JSON.
import Anthropic from "@anthropic-ai/sdk";
import { itemSchema, PROMPT, toItems, type AttemptTrace, type IdentifyResult, type RawItem } from "./gemini.js";

export const CLAUDE_IDENTIFY_MODEL = "claude-haiku-5-5";
const MAX_ITEMS = 6;
const TIMEOUT_MS = 15000;

type Schema = Record<string, unknown>;
const UNSUPPORTED = new Set(["minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minItems", "maxItems", "minLength", "maxLength"]);

/**
 * Strict tool schemas don't take numeric/length constraints, and want every object closed. Nullable
 * `type: ["string", "null"]` (+ enum) becomes `anyOf: [{type: "string", enum}, {type: "null"}]`.
 * The constraints we drop are enforced in code (item cap) or don't matter (0–1 ranges, box length 4 checked).
 */
export function toStrictSchema(node: unknown): unknown {
  if (Array.isArray(node)) return node.map(toStrictSchema);
  if (!node || typeof node !== "object") return node;
  const src = node as Schema;
  const out: Schema = {};
  for (const [k, v] of Object.entries(src)) {
    if (UNSUPPORTED.has(k)) continue;
    out[k] = k === "properties" ? Object.fromEntries(Object.entries(v as Schema).map(([pk, pv]) => [pk, toStrictSchema(pv)])) : toStrictSchema(v);
  }
  if (Array.isArray(out.type)) {
    const types = out.type as string[];
    const values = Array.isArray(out.enum) ? (out.enum as unknown[]) : null;
    const { type: _t, enum: _e, ...rest } = out;
    void _t;
    void _e;
    return {
      ...rest,
      anyOf: types.map((t) =>
        t === "null" ? { type: "null" } : { type: t, ...(values && { enum: values.filter((x) => x !== null) }) },
      ),
    };
  }
  if (out.type === "object") out.additionalProperties = false;
  return out;
}

const REPORT_TOOL: Anthropic.Tool = {
  name: "report_items",
  description:
    "Report every distinct purchasable product visible in the photo, most prominent first, with a tight box_2d " +
    "([ymin, xmin, ymax, xmax] on a 0-1000 grid over the image).",
  strict: true,
  input_schema: toStrictSchema({
    type: "object",
    properties: { items: { type: "array", items: itemSchema } },
    required: ["items"],
    additionalProperties: false,
  }) as Anthropic.Tool.InputSchema,
};

/** One Claude call. Throws the SDK's typed errors (RateLimitError, APIError, …) for the caller to handle. */
export async function identifyWithClaude(
  apiKey: string,
  jpeg: Buffer,
  model = CLAUDE_IDENTIFY_MODEL,
  signal?: AbortSignal,
): Promise<IdentifyResult & { ms: number }> {
  const client = new Anthropic({ apiKey, maxRetries: 1, timeout: TIMEOUT_MS });
  const began = performance.now();
  const res = await client.messages.create({
    model,
    max_tokens: 4096,
    output_config: { effort: "low" },
    tools: [REPORT_TOOL],
    // Haiku 5.5 accepts a forced tool call, and then answers with the tool call directly (no thinking pass).
    tool_choice: { type: "tool", name: REPORT_TOOL.name },
    messages: [
      {
        role: "user",
        content: [
          { type: "image", source: { type: "base64", media_type: "image/jpeg", data: jpeg.toString("base64") } },
          { type: "text", text: PROMPT },
        ],
      },
    ],
  }, { signal });
  const ms = Math.round(performance.now() - began);
  const trace: AttemptTrace[] = [
    {
      model,
      role: "race",
      startedAtMs: 0,
      ms,
      outcome: "ok",
      promptTokens: res.usage.input_tokens,
      outputTokens: res.usage.output_tokens,
    },
  ];
  if (res.stop_reason === "refusal") throw new Error(`Claude declined (${res.stop_details?.category ?? "no category"})`);
  const call = res.content.find((b): b is Anthropic.ToolUseBlock => b.type === "tool_use" && b.name === REPORT_TOOL.name);
  if (!call) throw new Error(`Claude returned no ${REPORT_TOOL.name} call (stop_reason ${res.stop_reason})`);
  const items = toItems(((call.input as { items?: RawItem[] }).items ?? []).slice(0, MAX_ITEMS));
  return { items, model, trace, ms };
}
