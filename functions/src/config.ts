import { defineSecret } from "firebase-functions/params";

// Deployed: Cloud Secret Manager. Emulator: functions/.env.local (never deployed, gitignored).
export const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");
export const CHANNEL3_API_KEY = defineSecret("CHANNEL3_API_KEY");
export const PAYPAL_CLIENT_ID = defineSecret("PAYPAL_CLIENT_ID");
export const PAYPAL_CLIENT_SECRET = defineSecret("PAYPAL_CLIENT_SECRET");
/** HMAC key for the items and offer tokens the app passes back to us. */
export const FETCH_SIGNING_KEY = defineSecret("FETCH_SIGNING_KEY");

/** Sandbox only for the hackathon. */
export const PAYPAL_BASE_URL = process.env.PAYPAL_BASE_URL ?? "https://api-m.sandbox.paypal.com";
