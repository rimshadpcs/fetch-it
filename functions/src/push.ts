// Push notifications (FCM). Devices register their token with registerPushToken; purchases that need the user
// (NEEDS_APPROVAL) and buys started by a price drop notify every registered device.

import { createHash } from "node:crypto";
import { ENFORCE_APP_CHECK } from "./options.js";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { FieldValue } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { userDoc } from "./lib/db.js";
import { requireUid } from "./purchase/purchase.js";

const devices = (uid: string) => userDoc(uid).collection("devices");
const tokenId = (token: string) => createHash("sha256").update(token).digest("hex").slice(0, 32);

/** The app sends its FCM token after sign-in and whenever FCM rotates it. */
export const registerPushToken = onCall<{ token: string; platform: string }>({ cors: true, enforceAppCheck: ENFORCE_APP_CHECK }, async (request) => {
  const uid = requireUid(request.auth?.uid);
  const { token, platform } = request.data ?? {};
  if (typeof token !== "string" || token.length < 20 || token.length > 4096) throw new HttpsError("invalid-argument", "token is required.");
  if (platform !== "android" && platform !== "ios") throw new HttpsError("invalid-argument", "platform must be android or ios.");
  await devices(uid).doc(tokenId(token)).set({ token, platform, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  logger.info("push token registered", { uid, platform, appCheck: request.app ? "valid" : "missing" });
  return { registered: true };
});

/**
 * What a tap opens: `approve` → the approval sheet for purchaseId, `purchase` → that purchase's outcome,
 * `watch` → Price watches (a drop that hasn't reached the target yet; watchId set, no purchase).
 * Data values must be strings (FCM).
 */
export type Push =
  | { title: string; body: string; open: "approve" | "purchase"; purchaseId: string }
  | { title: string; body: string; open: "watch"; watchId: string };

/** Best effort: a failed push is logged, never fails the purchase. Tokens FCM says are dead are removed. */
export async function notify(uid: string, push: Push): Promise<void> {
  try {
    const snap = await devices(uid).get();
    if (snap.empty) return;
    const tokens = snap.docs.map((d) => d.data().token as string);
    const data: Record<string, string> = push.open === "watch" ? { open: push.open, watchId: push.watchId } : { open: push.open, purchaseId: push.purchaseId };
    // One notification per purchase / watch: a later drop on the same watch replaces the earlier one.
    const tag = push.open === "watch" ? `watch-${push.watchId}` : push.purchaseId;
    const res = await getMessaging().sendEachForMulticast({
      tokens,
      notification: { title: push.title, body: push.body },
      data,
      android: { priority: "high", notification: { channelId: "approvals", tag } },
      apns: { payload: { aps: { sound: "default" } } },
    });
    const dead = res.responses
      .map((r, i) => (r.error && ["messaging/registration-token-not-registered", "messaging/invalid-argument"].includes(r.error.code) ? snap.docs[i] : null))
      .filter((d) => d !== null);
    await Promise.all(dead.map((d) => d.ref.delete()));
    logger.info("push sent", { uid, tag, open: push.open, sent: res.successCount, failed: res.failureCount, removed: dead.length });
  } catch (e) {
    logger.warn("push failed", { uid, open: push.open, message: (e as Error).message });
  }
}
