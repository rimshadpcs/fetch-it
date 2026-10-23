import { setGlobalOptions } from "firebase-functions/v2";
import { defineInt } from "firebase-functions/params";

// Imported first by index.ts: ES module imports run before index.ts's own body, so calling
// setGlobalOptions there would come after every other function was already defined.
// minInstances 0 everywhere: no idle cost. For demo recording, warm fetchItem/purchaseOffer (see DEPLOY.md).
setGlobalOptions({ region: "us-central1", maxInstances: 10, minInstances: 0 });

/**
 * Warm instances for the demo-critical callables (fetchItem, purchaseOffer) only. 0 normally; set
 * DEMO_MIN_INSTANCES=1 in .env.fetchit-437a0 and redeploy those two while recording (DEPLOY.md).
 */
export const DEMO_MIN_INSTANCES = defineInt("DEMO_MIN_INSTANCES", { default: 0 });
