// When PayPal tells us a dispute was opened, Scout reads it with the PayPal Agent Toolkit's own tools
// (get_dispute, get_order) and writes one or two plain sentences for the Activity feed. Gemini drives the tool calls;
// the toolkit makes the PayPal calls. Read-only: the agent is only given tools that look things up, never ones that
// accept a claim, refund or create anything. Best effort: any failure falls back to the template summary.

import { GoogleGenAI, type Content, type FunctionDeclaration } from "@google/genai";
import { logger } from "firebase-functions";
import { meter } from "../lib/meter.js";

const MODEL = "gemini-3.6-flash";
const READ_ONLY = new Set(["get_dispute", "get_order"]);

export interface DisputeContext {
  disputeId: string;
  title: string;
  storeName: string;
  dogName: string;
}

export async function summarizeDispute(
  keys: { gemini: string; paypalClientId: string; paypalClientSecret: string },
  ctx: DisputeContext,
): Promise<{ summary: string; toolCalls: string[] } | null> {
  const started = Date.now();
  const toolCalls: string[] = [];
  try {
    // Loaded only here: the toolkit pulls in a lot, and no other function needs it.
    const { PayPalAgentToolkit } = await import("@paypal/agent-toolkit/openai");
    const toolkit = new PayPalAgentToolkit({
      clientId: keys.paypalClientId,
      clientSecret: keys.paypalClientSecret,
      configuration: { actions: { disputes: { get: true }, orders: { get: true } }, context: { sandbox: true } },
    });
    const tools = toolkit.getTools().filter((t) => READ_ONLY.has(t.function.name));
    const declarations: FunctionDeclaration[] = tools.map((t) => ({
      name: t.function.name,
      description: t.function.description,
      parametersJsonSchema: t.function.parameters,
    }));

    const ai = new GoogleGenAI({ apiKey: keys.gemini });
    const contents: Content[] = [
      {
        role: "user",
        parts: [
          {
            text:
              `PayPal dispute ${ctx.disputeId} was just opened by the buyer about "${ctx.title}" from ${ctx.storeName}. ` +
              `Look it up with get_dispute. Then write the buyer's activity-feed note: at most two short sentences, ` +
              `second person ("You…"), plain words, no IDs, no markdown. Say what they disputed and why (in their words if the ` +
              `dispute has them), the amount, and what happens next according to PayPal (e.g. who must respond and by when). ` +
              `Don't promise an outcome and don't give advice.`,
          },
        ],
      },
    ];
    for (let step = 0; step < 4; step++) {
      meter.record("gemini");
      const res = await ai.models.generateContent({
        model: MODEL,
        contents,
        config: {
          systemInstruction: `You are ${ctx.dogName}, the shopping dog in the Fetch It app, explaining a PayPal dispute to your owner.`,
          tools: [{ functionDeclarations: declarations }],
          abortSignal: AbortSignal.timeout(20_000),
        },
      });
      const calls = res.functionCalls ?? [];
      if (calls.length === 0) {
        const text = res.text?.trim().replace(/\s+/g, " ");
        if (!text) return null;
        logger.info("dispute agent", { disputeId: ctx.disputeId, toolCalls, ms: Date.now() - started });
        return { summary: text.slice(0, 400), toolCalls };
      }
      contents.push(res.candidates![0].content!);
      const parts = [];
      for (const call of calls) {
        if (!call.name || !READ_ONLY.has(call.name)) throw new Error(`agent asked for a tool it doesn't have: ${call.name}`);
        toolCalls.push(call.name);
        const out = await toolkit.handleToolCall({ id: call.id ?? call.name, type: "function", function: { name: call.name, arguments: JSON.stringify(call.args ?? {}) } });
        const content = typeof out.content === "string" ? out.content : JSON.stringify(out.content);
        // An error from PayPal (not found, auth) must not become the user's note: use the template instead.
        if (call.name === "get_dispute" && !content.includes(ctx.disputeId)) throw new Error(`get_dispute returned no dispute: ${content.slice(0, 200)}`);
        parts.push({ functionResponse: { id: call.id, name: call.name, response: { result: content.slice(0, 20_000) } } });
      }
      contents.push({ role: "user", parts });
    }
    return null;
  } catch (e) {
    logger.warn("dispute agent failed", { disputeId: ctx.disputeId, toolCalls, message: (e as Error).message });
    return null;
  }
}
