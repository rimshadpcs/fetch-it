// Registers our paypalWebhook URL with the PayPal sandbox app (once), or shows the existing registration.
// Prints only the webhook id (not a secret): put it in functions/.env.fetchit-437a0 as PAYPAL_WEBHOOK_ID and redeploy
// paypalWebhook. Run: npm run webhook:register
import { config } from "dotenv";
import { PayPal } from "../lib/lib/paypal.js";
import { WEBHOOK_EVENTS } from "../lib/webhooks/events.js";

config({ path: new URL("../.env.local", import.meta.url).pathname, quiet: true });
const URL_ = "https://us-central1-fetchit-437a0.cloudfunctions.net/paypalWebhook";
const pp = new PayPal("https://api-m.sandbox.paypal.com", process.env.PAYPAL_CLIENT_ID, process.env.PAYPAL_CLIENT_SECRET);

const existing = (await pp.listWebhooks()).webhooks.find((w) => w.url === URL_);
if (existing) {
  const have = new Set(existing.event_types.map((e) => e.name));
  console.log(`Already registered: PAYPAL_WEBHOOK_ID=${existing.id}`);
  const missing = WEBHOOK_EVENTS.filter((e) => !have.has(e));
  if (missing.length) console.log(`Missing event types (update it in the developer dashboard): ${missing.join(", ")}`);
} else {
  const w = await pp.createWebhook(URL_, [...WEBHOOK_EVENTS]);
  console.log(`Registered ${w.url}\nPAYPAL_WEBHOOK_ID=${w.id}`);
}
