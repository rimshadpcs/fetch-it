// Spike 2: approve PayPal once with vaulting, then charge again server-side with no redirect.
// Run from server/:  node --env-file=.env spikes/spike2.mjs <create|capture|charge>
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";

const BASE = process.env.PAYPAL_BASE_URL;
const OUT = new URL("../fixtures/out/", import.meta.url);
const CALLBACK = "http://localhost:8787";

const mask = (s) => (s ? `${s.slice(0, 4)}…${s.slice(-4)} (${s.length} chars)` : s);

async function token() {
  const auth = Buffer.from(`${process.env.PAYPAL_CLIENT_ID}:${process.env.PAYPAL_CLIENT_SECRET}`).toString("base64");
  const res = await fetch(`${BASE}/v1/oauth2/token`, {
    method: "POST",
    headers: { Authorization: `Basic ${auth}`, "Content-Type": "application/x-www-form-urlencoded" },
    body: "grant_type=client_credentials",
  });
  if (!res.ok) fail("oauth", res.status, await res.json());
  return (await res.json()).access_token;
}

async function paypal(method, path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${await token()}`,
      "Content-Type": "application/json",
      "PayPal-Request-Id": randomUUID(),
      Prefer: "return=representation",
    },
    body: body && JSON.stringify(body),
  });
  const json = await res.json();
  if (!res.ok) fail(`${method} ${path}`, res.status, json);
  return json;
}

function fail(step, status, json) {
  console.error(`FAILED ${step}: HTTP ${status}`);
  console.error(JSON.stringify(json, null, 2));
  process.exit(1);
}

const save = async (name, data) => {
  await mkdir(OUT, { recursive: true });
  await writeFile(new URL(name, OUT), JSON.stringify(data, null, 2) + "\n");
};
const load = async (name) => JSON.parse(await readFile(new URL(name, OUT), "utf8"));

async function create() {
  const order = await paypal("POST", "/v2/checkout/orders", {
    intent: "CAPTURE",
    purchase_units: [{ reference_id: "spike2-first", description: "Fetch It vault test", amount: { currency_code: "USD", value: "1.00" } }],
    payment_source: {
      paypal: {
        attributes: { vault: { store_in_vault: "ON_SUCCESS", usage_type: "MERCHANT", customer_type: "CONSUMER" } },
        experience_context: {
          brand_name: "Fetch It",
          shipping_preference: "GET_FROM_FILE",
          user_action: "PAY_NOW",
          return_url: `${CALLBACK}/return`,
          cancel_url: `${CALLBACK}/cancel`,
        },
      },
    },
  });
  await save("order1.json", { id: order.id });
  const approve = order.links.find((l) => l.rel === "payer-action" || l.rel === "approve");
  console.log("order id:", order.id, "status:", order.status);
  console.log("approval URL:", approve?.href);
}

// Diagnostic: same order without vault attributes, to tell vault problems apart from buyer/login problems.
async function plain() {
  const order = await paypal("POST", "/v2/checkout/orders", {
    intent: "CAPTURE",
    purchase_units: [{ reference_id: "spike2-plain", amount: { currency_code: "USD", value: "1.00" } }],
    payment_source: {
      paypal: {
        experience_context: {
          brand_name: "Fetch It",
          shipping_preference: "GET_FROM_FILE",
          user_action: "PAY_NOW",
          return_url: `${CALLBACK}/return`,
          cancel_url: `${CALLBACK}/cancel`,
        },
      },
    },
  });
  const approve = order.links.find((l) => l.rel === "payer-action" || l.rel === "approve");
  console.log("plain order id:", order.id, "status:", order.status);
  console.log("approval URL:", approve?.href);
}

async function capture() {
  const { id } = await load("order1.json");
  const order = await paypal("POST", `/v2/checkout/orders/${id}/capture`, {});
  const cap = order.purchase_units[0].payments?.captures?.[0];
  console.log("order status:", order.status, "| capture:", cap?.status, cap?.amount?.value, cap?.amount?.currency_code);

  const vault = order.payment_source?.paypal?.attributes?.vault;
  console.log("vault status:", vault?.status, "| vault id:", mask(vault?.id));
  if (!vault?.id) {
    console.error("No vault id in capture response. payment_source was:");
    console.error(JSON.stringify(order.payment_source, null, 2));
    process.exit(1);
  }
  await save("vault.json", {
    vault_id: vault.id,
    status: vault.status,
    customer_id: vault.customer?.id,
    payer_email: order.payment_source.paypal.email_address,
    first_order_id: order.id,
    saved_at: new Date().toISOString(),
  });
  console.log("saved fixtures/out/vault.json");
  console.log("shipping:", JSON.stringify(order.purchase_units[0].shipping, null, 2));
}

async function charge() {
  const { vault_id } = await load("vault.json");
  let order = await paypal("POST", "/v2/checkout/orders", {
    intent: "CAPTURE",
    purchase_units: [{ reference_id: "spike2-repeat", description: "Fetch It repeat charge", amount: { currency_code: "USD", value: "2.00" } }],
    payment_source: { paypal: { vault_id } },
  });
  console.log("created:", order.id, "status:", order.status);
  // Vaulted orders may complete on creation; capture only if still waiting.
  if (order.status === "APPROVED" || order.status === "CREATED") {
    order = await paypal("POST", `/v2/checkout/orders/${order.id}/capture`, {});
  }
  const cap = order.purchase_units[0].payments?.captures?.[0];
  console.log("final order status:", order.status, "| capture:", cap?.status, cap?.amount?.value, cap?.amount?.currency_code, cap?.id);
  await save("order2.json", { id: order.id, status: order.status, capture_id: cap?.id, capture_status: cap?.status });
}

const steps = { create, plain, capture, charge };
const step = steps[process.argv[2]];
if (!step) {
  console.error("usage: node --env-file=.env spikes/spike2.mjs <create|capture|charge>");
  process.exit(1);
}
await step();
