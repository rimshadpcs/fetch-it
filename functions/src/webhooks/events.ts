// PayPal webhook events → what we need from them. Pure (unit-tested): no I/O.

export const WEBHOOK_EVENTS = [
  "PAYMENT.CAPTURE.COMPLETED",
  "PAYMENT.CAPTURE.DENIED",
  "PAYMENT.CAPTURE.REFUNDED",
  "PAYMENT.CAPTURE.REVERSED",
  "CUSTOMER.DISPUTE.CREATED",
  "CUSTOMER.DISPUTE.UPDATED",
  "CUSTOMER.DISPUTE.RESOLVED",
  "VAULT.PAYMENT-TOKEN.DELETED",
] as const;

export interface PayPalEvent {
  id: string;
  event_type: string;
  create_time?: string;
  resource?: Record<string, unknown>;
}

export type Parsed =
  | { kind: "capture"; status: "completed" | "denied"; captureId: string }
  | { kind: "refund"; reversed: boolean; refundId: string; captureId: string; amount: number; currency: string; note: string | null }
  | {
      kind: "dispute";
      stage: "created" | "updated" | "resolved";
      disputeId: string;
      captureIds: string[];
      reason: string | null;
      status: string | null;
      outcome: string | null;
      amount: number | null;
      currency: string | null;
    }
  | { kind: "unlinked"; vaultId: string }
  | { kind: "ignored"; why: string };

const str = (v: unknown) => (typeof v === "string" && v ? v : null);
const money = (v: unknown): { value: number; currency: string } | null => {
  const m = v as { value?: string; currency_code?: string } | undefined;
  const n = Number(m?.value);
  return m && Number.isFinite(n) ? { value: n, currency: m.currency_code ?? "USD" } : null;
};

/** The capture a refund belongs to: its `up` link ends in /captures/{id}. */
export function captureOfRefund(resource: Record<string, unknown>): string | null {
  const links = (resource.links as { rel?: string; href?: string }[] | undefined) ?? [];
  const up = links.find((l) => l.rel === "up" && /\/captures\//.test(l.href ?? ""));
  return up?.href?.match(/\/captures\/([A-Z0-9]+)/i)?.[1] ?? null;
}

export function parseEvent(e: PayPalEvent): Parsed {
  const r = e.resource ?? {};
  switch (e.event_type) {
    case "PAYMENT.CAPTURE.COMPLETED":
    case "PAYMENT.CAPTURE.DENIED": {
      const captureId = str(r.id);
      if (!captureId) return { kind: "ignored", why: "capture without id" };
      return { kind: "capture", status: e.event_type.endsWith("COMPLETED") ? "completed" : "denied", captureId };
    }
    case "PAYMENT.CAPTURE.REFUNDED":
    case "PAYMENT.CAPTURE.REVERSED": {
      const refundId = str(r.id);
      // A reversal's resource is the capture itself in some payloads; then the capture id is the resource id.
      const captureId = captureOfRefund(r) ?? (e.event_type.endsWith("REVERSED") ? str(r.id) : null);
      const amt = money(r.amount);
      if (!refundId || !captureId || !amt) return { kind: "ignored", why: "refund without id / capture / amount" };
      return {
        kind: "refund",
        reversed: e.event_type.endsWith("REVERSED"),
        refundId,
        captureId,
        amount: amt.value,
        currency: amt.currency,
        note: str(r.note_to_payer),
      };
    }
    case "CUSTOMER.DISPUTE.CREATED":
    case "CUSTOMER.DISPUTE.UPDATED":
    case "CUSTOMER.DISPUTE.RESOLVED": {
      const disputeId = str(r.dispute_id);
      const txs = (r.disputed_transactions as { seller_transaction_id?: string }[] | undefined) ?? [];
      const captureIds = txs.map((t) => t.seller_transaction_id).filter((x): x is string => !!x);
      if (!disputeId || captureIds.length === 0) return { kind: "ignored", why: "dispute without id / transaction" };
      const amt = money(r.dispute_amount);
      const outcome = (r.dispute_outcome as { outcome_code?: string } | undefined)?.outcome_code ?? null;
      return {
        kind: "dispute",
        stage: e.event_type.endsWith("CREATED") ? "created" : e.event_type.endsWith("RESOLVED") ? "resolved" : "updated",
        disputeId,
        captureIds,
        reason: str(r.reason),
        status: str(r.status),
        outcome,
        amount: amt?.value ?? null,
        currency: amt?.currency ?? null,
      };
    }
    case "VAULT.PAYMENT-TOKEN.DELETED": {
      const vaultId = str(r.id);
      return vaultId ? { kind: "unlinked", vaultId } : { kind: "ignored", why: "token without id" };
    }
    default:
      return { kind: "ignored", why: `not handled: ${e.event_type}` };
  }
}

const REASONS: Record<string, string> = {
  MERCHANDISE_OR_SERVICE_NOT_RECEIVED: "it never arrived",
  MERCHANDISE_OR_SERVICE_NOT_AS_DESCRIBED: "it wasn't as described",
  UNAUTHORISED: "you didn't authorise the payment",
  CREDIT_NOT_PROCESSED: "a refund wasn't processed",
  DUPLICATE_TRANSACTION: "you were charged twice",
  INCORRECT_AMOUNT: "the amount was wrong",
  PAYMENT_BY_OTHER_MEANS: "you paid another way",
  CANCELED_RECURRING_BILLING: "billing was cancelled",
  PROBLEM_WITH_REMITTANCE: "there was a problem with the payment",
  OTHER: "something else went wrong",
};

export const reasonText = (reason: string | null) => (reason && REASONS[reason]) || "something went wrong";

/** Plain text when the agent can't summarize (or for updates / resolutions). */
export function disputeSummary(d: Extract<Parsed, { kind: "dispute" }>, title: string, fmt: (n: number, c: string) => string): string {
  const amount = d.amount !== null && d.currency ? fmt(d.amount, d.currency) : null;
  if (d.stage === "created") {
    return `You opened a PayPal dispute for ${title}: ${reasonText(d.reason)}.${amount ? ` PayPal is holding ${amount} while it looks into it.` : ""}`;
  }
  if (d.stage === "resolved") {
    const won = d.outcome === "RESOLVED_BUYER_FAVOUR" || d.outcome === "ACCEPTED";
    return won ? `PayPal settled your dispute for ${title} in your favour.${amount ? ` ${amount} back to your PayPal.` : ""}` : `PayPal closed your dispute for ${title}.`;
  }
  return `Your PayPal dispute for ${title} was updated${d.status ? ` (${d.status.toLowerCase().replace(/_/g, " ")})` : ""}.`;
}
