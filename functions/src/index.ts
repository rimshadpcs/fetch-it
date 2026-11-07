import "./options.js"; // must stay first
import { onRequest } from "firebase-functions/v2/https";

export const health = onRequest((_req, res) => {
  res.json({ ok: true, service: "fetch-it", time: new Date().toISOString() });
});

export { fetchItem } from "./fetch/fetchItem.js";
export { purchaseOffer, approvePurchase, undoPurchase } from "./purchase/purchase.js";
export { watchOffer, stopWatch, checkWatches, checkWatchesNow } from "./purchase/watch.js";
export { findSimilar } from "./fetch/findSimilar.js";
export { linkPhoto } from "./fetch/linkPhoto.js";
export { advanceOrders, advanceOrder, cancelOrder } from "./fulfillment.js";
export { startPayPalLink, completePayPalLink, confirmPlace } from "./paypalLink.js";
export { sweepStuckPurchases, runMaintenance } from "./purchase/cleanup.js";
export { setProfile, setRules } from "./settings.js";
export { addPlace, deletePlace, setDefaultPlace } from "./places.js";
export { saveItem, savedOffer } from "./saved.js";
export { registerPushToken } from "./push.js";
