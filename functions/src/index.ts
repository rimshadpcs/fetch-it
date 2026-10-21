import { onRequest } from "firebase-functions/v2/https";
import { setGlobalOptions } from "firebase-functions/v2";

setGlobalOptions({ region: "us-central1", maxInstances: 10 });

export const health = onRequest((_req, res) => {
  res.json({ ok: true, service: "fetch-it", time: new Date().toISOString() });
});

export { fetchItem } from "./fetch/fetchItem.js";
