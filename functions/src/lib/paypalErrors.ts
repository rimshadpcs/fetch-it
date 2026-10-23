import { HttpsError } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { PayPalError } from "./paypal.js";

/**
 * Log a PayPal failure with PayPal's error name, issue, status and debug_id (never secrets), and turn it into
 * an HttpsError the app can show, instead of a bare INTERNAL. Non-PayPal errors pass through unchanged.
 */
export function paypalFailure(e: unknown, where: string, context: Record<string, unknown> = {}): unknown {
  if (!(e instanceof PayPalError)) return e;
  logger.error(`PayPal call failed in ${where}`, { where, ...context, ...e.toLog() });
  const what = [e.paypalName, e.issue].filter(Boolean).join(" / ") || `HTTP ${e.status}`;
  return new HttpsError("unavailable", `PayPal error: ${what}${e.debugId ? ` (debug_id ${e.debugId})` : ""}`, {
    paypalError: e.paypalName,
    issue: e.issue,
    status: e.status,
    debugId: e.debugId,
  });
}
