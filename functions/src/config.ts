import { defineSecret } from "firebase-functions/params";

// Deployed: Cloud Secret Manager. Emulator: functions/.env.local (never deployed, gitignored).
export const GEMINI_API_KEY = defineSecret("GEMINI_API_KEY");
export const CHANNEL3_API_KEY = defineSecret("CHANNEL3_API_KEY");
