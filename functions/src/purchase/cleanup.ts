import { onCall } from "firebase-functions/v2/https";
import { ENFORCE_APP_CHECK } from "../options.js";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { logger } from "firebase-functions";
import { Timestamp } from "firebase-admin/firestore";
import { db } from "../lib/db.js";
import { requireDebug, runOrderSweep } from "../fulfillment.js";
import { charge, requireUid, secrets, type ClaimRule, type PurchaseDoc } from "./purchase.js";

/**
 * A purchase is stuck when its request died mid-flight (instance recycled during the undo window, crash
 * after approval, timeout while charging). Two minutes is far past any normal path.
 */
export const STUCK_AFTER_MS = 2 * 60 * 1000;

const ms = (t: unknown) => (t instanceof Timestamp ? t.toMillis() : Number.POSITIVE_INFINITY);

/** Same transactional claim as the normal path, so the two can never both charge. */
export const stuckRule: ClaimRule = (p: PurchaseDoc, now: number) => {
  const cutoff = now - STUCK_AFTER_MS;
  if (p.status === "pending_undo") return ms(p.undoUntil) < cutoff;
  if (p.status === "approved") return ms(p.updatedAt) < cutoff;
  // A charge that started long ago and never finished. Re-sending it is safe: same PayPal-Request-Id
  // returns the same PayPal order, so this completes it rather than charging again.
  if (p.status === "charging") return ms(p.chargeStartedAt) < cutoff;
  return false;
};

/** Finish (charge) or fail every stuck purchase. Each ends with an activity entry marked recoveredBy. */
export async function runStuckSweep(): Promise<{ found: number; completed: number; failed: number; skipped: number }> {
  const snap = await db.collectionGroup("purchases").where("status", "in", ["pending_undo", "approved", "charging"]).get();
  const now = Date.now();
  const stuck = snap.docs.filter((d) => stuckRule(d.data() as PurchaseDoc, now));
  const tally = { found: stuck.length, completed: 0, failed: 0, skipped: 0 };
  for (const d of stuck) {
    const uid = d.ref.parent.parent!.id;
    try {
      const after = await charge(uid, d.ref, stuckRule, "cleanup");
      if (after.recoveredBy !== "cleanup") tally.skipped++; // someone else resolved it first
      else if (after.status === "completed") tally.completed++;
      else tally.failed++;
    } catch (e) {
      tally.failed++;
      logger.error("stuck purchase cleanup failed", { path: d.ref.path, message: (e as Error).message });
    }
  }
  if (stuck.length) logger.info("stuck purchase sweep", tally);
  return tally;
}

export const sweepStuckPurchases = onSchedule({ schedule: "every 5 minutes", secrets, timeoutSeconds: 300 }, async () => {
  await runStuckSweep();
});

/** Debug-only: run both sweeps now (emulator, or ALLOW_DEBUG=true). */
export const runMaintenance = onCall({ secrets, cors: true, enforceAppCheck: ENFORCE_APP_CHECK, timeoutSeconds: 120 }, async (request) => {
  requireDebug();
  requireUid(request.auth?.uid);
  return { stuck: await runStuckSweep(), orders: await runOrderSweep() };
});
