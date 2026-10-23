import { setGlobalOptions } from "firebase-functions/v2";

// Imported first by index.ts: ES module imports run before index.ts's own body, so calling
// setGlobalOptions there would come after every other function was already defined.
// minInstances 0 everywhere: no idle cost. For demo recording, warm fetchItem/purchaseOffer (see DEPLOY.md).
setGlobalOptions({ region: "us-central1", maxInstances: 10, minInstances: 0 });
