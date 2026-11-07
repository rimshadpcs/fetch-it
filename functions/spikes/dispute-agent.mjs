// Runs Scout's dispute agent (PayPal Agent Toolkit tools driven by Gemini) against the sandbox.
// node spikes/dispute-agent.mjs [disputeId]   (no id: lists open sandbox disputes with the toolkit first)
import { config } from "dotenv";
import { logger } from "firebase-functions";
import { summarizeDispute } from "../lib/webhooks/disputeAgent.js";

config({ path: new URL("../.env.local", import.meta.url).pathname, quiet: true });
const keys = { gemini: process.env.GEMINI_API_KEY, paypalClientId: process.env.PAYPAL_CLIENT_ID, paypalClientSecret: process.env.PAYPAL_CLIENT_SECRET };

let id = process.argv[2];
if (!id) {
  const { PayPalAgentToolkit } = await import("@paypal/agent-toolkit/openai");
  const tk = new PayPalAgentToolkit({ clientId: keys.paypalClientId, clientSecret: keys.paypalClientSecret, configuration: { actions: { disputes: { list: true } }, context: { sandbox: true } } });
  const out = await tk.handleToolCall({ id: "1", type: "function", function: { name: "list_disputes", arguments: "{}" } });
  console.log("list_disputes →", String(out.content).slice(0, 400));
  id = String(out.content).match(/PP-[A-Z]-\d+/)?.[0] ?? "PP-D-DOES-NOT-EXIST";
}
const t = Date.now();
const r = await summarizeDispute(keys, { disputeId: id, title: "Glass Mushroom Lamp", storeName: "wayfair.com", dogName: "Scout" });
console.log(`${id} → ${JSON.stringify(r)} in ${Date.now() - t} ms`);
void logger;
