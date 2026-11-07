// PayPal webhook events → what we act on (pure). Run: npm test
import assert from "node:assert/strict";
import { test } from "node:test";
import { captureOfRefund, disputeSummary, parseEvent } from "../lib/webhooks/events.js";

const fmt = (n) => `$${n.toFixed(2)}`;

test("refund: id, amount and the capture from its up link", () => {
  const p = parseEvent({
    id: "WH-1",
    event_type: "PAYMENT.CAPTURE.REFUNDED",
    resource: {
      id: "1JU08902781691411",
      amount: { value: "41.99", currency_code: "USD" },
      note_to_payer: "Sorry",
      links: [
        { rel: "self", href: "https://api.sandbox.paypal.com/v2/payments/refunds/1JU08902781691411" },
        { rel: "up", href: "https://api.sandbox.paypal.com/v2/payments/captures/2GG279541U471931P" },
      ],
    },
  });
  assert.deepEqual(p, { kind: "refund", reversed: false, refundId: "1JU08902781691411", captureId: "2GG279541U471931P", amount: 41.99, currency: "USD", note: "Sorry" });
  assert.equal(captureOfRefund({ links: [] }), null);
});

test("dispute: id, disputed capture, reason, amount; template text", () => {
  const p = parseEvent({
    id: "WH-2",
    event_type: "CUSTOMER.DISPUTE.CREATED",
    resource: {
      dispute_id: "PP-D-1234",
      reason: "MERCHANDISE_OR_SERVICE_NOT_RECEIVED",
      status: "OPEN",
      dispute_amount: { value: "41.99", currency_code: "USD" },
      disputed_transactions: [{ seller_transaction_id: "2GG279541U471931P" }],
    },
  });
  assert.equal(p.kind, "dispute");
  assert.deepEqual(p.captureIds, ["2GG279541U471931P"]);
  assert.match(disputeSummary(p, "Glass lamp", fmt), /never arrived\. PayPal is holding \$41\.99/);
  assert.match(disputeSummary({ ...p, stage: "resolved", outcome: "RESOLVED_BUYER_FAVOUR" }, "Glass lamp", fmt), /in your favour/);
});

test("unlinked vault token, captures, and things we ignore", () => {
  assert.deepEqual(parseEvent({ id: "3", event_type: "VAULT.PAYMENT-TOKEN.DELETED", resource: { id: "8kk8451t" } }), { kind: "unlinked", vaultId: "8kk8451t" });
  assert.deepEqual(parseEvent({ id: "4", event_type: "PAYMENT.CAPTURE.DENIED", resource: { id: "CAP1" } }), { kind: "capture", status: "denied", captureId: "CAP1" });
  assert.equal(parseEvent({ id: "5", event_type: "BILLING.PLAN.CREATED", resource: {} }).kind, "ignored");
  assert.equal(parseEvent({ id: "6", event_type: "PAYMENT.CAPTURE.REFUNDED", resource: { id: "R" } }).kind, "ignored");
});
