// First-time PayPal link (onboarding). Uses a Vault v3 setup token: the buyer approves once and we get a
// reusable payment token WITHOUT charging anything, so there's no verification charge to refund.
// The vault token stays server-side in vault/{uid}; the app only learns "linked" + email + suggested address.

import { HttpsError, onCall } from "firebase-functions/v2/https";
import { ENFORCE_APP_CHECK } from "./options.js";
import { logger } from "firebase-functions";
import { FieldValue } from "firebase-admin/firestore";
import { PAYPAL_CLIENT_ID, PAYPAL_CLIENT_SECRET } from "./config.js";
import { db, userDoc } from "./lib/db.js";
import { PayPalError, type PayPalAddress } from "./lib/paypal.js";
import { paypalFailure } from "./lib/paypalErrors.js";
import { clients, requireUid } from "./purchase/purchase.js";

const RETURN_URL = "fetchit://paypal/return";
const CANCEL_URL = "fetchit://paypal/cancel";
const secrets = [PAYPAL_CLIENT_ID, PAYPAL_CLIENT_SECRET];

/** Server-only record of who started which link (top-level, so clients can't read it). */
const linkRef = (setupTokenId: string) => db.collection("paypalLinks").doc(setupTokenId);

/** Step 1: get an approval URL. The app opens it in a browser sheet; PayPal returns to fetchit://paypal/… */
export const startPayPalLink = onCall({ secrets, cors: true, enforceAppCheck: ENFORCE_APP_CHECK }, async (request) => {
  const uid = requireUid(request.auth?.uid);
  const token = await clients()
    .paypal()
    .createSetupToken({ requestId: `link-${uid}-${Date.now()}`, returnUrl: RETURN_URL, cancelUrl: CANCEL_URL })
    .catch((e) => {
      throw paypalFailure(e, "startPayPalLink", { uid });
    });
  const approvalUrl = token.links.find((l) => l.rel === "approve")?.href;
  if (!approvalUrl) throw new HttpsError("internal", "PayPal didn't return an approval link.");
  await linkRef(token.id).set({ uid, status: "started", createdAt: FieldValue.serverTimestamp() });
  logger.info("PayPal link started", { uid, setupTokenId: token.id });
  return { setupTokenId: token.id, approvalUrl, returnUrl: RETURN_URL, cancelUrl: CANCEL_URL };
});

const toPlace = (fullName: string, a: PayPalAddress) => ({
  label: "Home",
  fullName,
  line1: a.address_line_1 ?? "",
  line2: a.address_line_2 ?? null,
  city: a.admin_area_2 ?? "",
  state: a.admin_area_1 ?? "",
  postalCode: a.postal_code ?? "",
  countryCode: a.country_code,
});

/**
 * Step 2, after the deep link returns: vault the approved setup token. `token` is the setupTokenId from step 1
 * (PayPal also puts it on the return URL as approval_token_id).
 */
export const completePayPalLink = onCall<{ token: string }>({ secrets, cors: true, enforceAppCheck: ENFORCE_APP_CHECK }, async (request) => {
  const uid = requireUid(request.auth?.uid);
  const setupTokenId = request.data?.token;
  if (typeof setupTokenId !== "string" || !/^[A-Za-z0-9-]{6,64}$/.test(setupTokenId)) throw new HttpsError("invalid-argument", "token is required.");

  const link = await linkRef(setupTokenId).get();
  if (!link.exists || link.data()?.uid !== uid) throw new HttpsError("permission-denied", "That PayPal link wasn't started by you.");
  if (link.data()?.status === "completed") throw new HttpsError("already-exists", "PayPal is already linked with this approval.");

  const paypal = clients().paypal();
  let pt;
  try {
    pt = await paypal.createPaymentToken(setupTokenId, `pt-${setupTokenId}`);
  } catch (e) {
    if (e instanceof PayPalError) logger.warn("createPaymentToken failed", { uid, setupTokenId, ...e.toLog() });
    const status = await paypal.getSetupToken(setupTokenId).then((t) => t.status).catch(() => "unknown");
    if (status !== "APPROVED") throw new HttpsError("failed-precondition", `Approve Fetch It in PayPal first (status: ${status}).`);
    throw paypalFailure(e, "completePayPalLink", { uid, setupTokenId });
  }

  const pp = pt.payment_source?.paypal ?? {};
  const name = pp.shipping?.name?.full_name ?? [pp.name?.given_name, pp.name?.surname].filter(Boolean).join(" ");
  // Prefer the shipping address the buyer picked in PayPal; fall back to the account address.
  const address = pp.shipping?.address?.address_line_1 ? pp.shipping.address : pp.address?.address_line_1 ? pp.address : null;
  const addressSource = address === pp.shipping?.address ? "paypal_shipping" : address ? "paypal_account" : null;

  const user = userDoc(uid);
  const suggested = await db.runTransaction(async (tx) => {
    const home = await tx.get(user.collection("places").doc("home"));
    // Never overwrite a Home the user already confirmed.
    const suggest = address && !(home.exists && !home.data()?.suggested);
    tx.set(db.collection("vault").doc(uid), {
      paypalVaultId: pt.id,
      paypalCustomerId: pt.customer?.id ?? null,
      payerId: pp.payer_id ?? null,
      linkedAt: FieldValue.serverTimestamp(),
    });
    tx.set(user, { paypalLinked: true, paypalEmail: pp.email_address ?? null, paypalLinkedAt: FieldValue.serverTimestamp() }, { merge: true });
    if (suggest) {
      tx.set(user.collection("places").doc("home"), {
        ...toPlace(name || "Home", address!),
        suggested: true,
        source: addressSource,
        isDefault: true,
        createdAt: FieldValue.serverTimestamp(),
      });
    }
    tx.update(linkRef(setupTokenId), { status: "completed", completedAt: FieldValue.serverTimestamp() });
    tx.set(user.collection("activity").doc(), {
      type: "paypal",
      status: "linked",
      summary: `PayPal linked${pp.email_address ? ` (${pp.email_address})` : ""}. Nothing was charged.`,
      createdAt: FieldValue.serverTimestamp(),
    });
    return suggest ? { placeId: "home", ...toPlace(name || "Home", address!), source: addressSource } : null;
  });

  logger.info("PayPal linked", { uid, suggestedPlace: !!suggested });
  // Deliberately no vault token in the response.
  return { paypalLinked: true, email: pp.email_address ?? null, suggestedPlace: suggested, charged: false };
});

/** The app shows the suggested Home; the user confirms it (only then does it count as a saved place). */
export const confirmPlace = onCall<{ placeId: string }>({ cors: true, enforceAppCheck: ENFORCE_APP_CHECK }, async (request) => {
  const uid = requireUid(request.auth?.uid);
  const placeId = request.data?.placeId;
  if (typeof placeId !== "string" || !/^[A-Za-z0-9_-]{1,40}$/.test(placeId)) throw new HttpsError("invalid-argument", "placeId is required.");
  const ref = userDoc(uid).collection("places").doc(placeId);
  const snap = await ref.get();
  if (!snap.exists) throw new HttpsError("not-found", "No such place.");
  // The confirmed place becomes the default unless another saved place already is.
  const hasDefault = (await userDoc(uid).collection("places").where("isDefault", "==", true).get()).docs.some((p) => p.id !== placeId && !p.data().suggested);
  await ref.update({ suggested: false, isDefault: !hasDefault, confirmedAt: FieldValue.serverTimestamp() });
  return { placeId, confirmed: true };
});
