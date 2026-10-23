import { HttpsError, onCall } from "firebase-functions/v2/https";
import { FieldValue } from "firebase-admin/firestore";
import { userDoc } from "./lib/db.js";
import { requireUid } from "./purchase/purchase.js";
import type { HouseRules } from "./rules.js";

const num = (v: unknown, name: string, max: number) => {
  if (typeof v !== "number" || !Number.isFinite(v) || v < 0 || v > max) throw new HttpsError("invalid-argument", `${name} must be 0–${max}.`);
  return Math.round(v * 100) / 100;
};
const bool = (v: unknown, name: string) => {
  if (typeof v !== "boolean") throw new HttpsError("invalid-argument", `${name} must be true or false.`);
  return v;
};

/** Save the user's house rules (the app's Set house rules screen). Clients read them straight from Firestore. */
export const setRules = onCall<Partial<HouseRules>>({ cors: true }, async (request) => {
  const uid = requireUid(request.auth?.uid);
  const d = request.data ?? {};
  const cats = d.allowedCategories;
  if (!Array.isArray(cats) || cats.length > 50 || !cats.every((c) => typeof c === "string" && /^[a-z0-9-]{1,80}$/.test(c))) {
    throw new HttpsError("invalid-argument", "allowedCategories must be Channel3 category slugs.");
  }
  const rules: HouseRules = {
    currency: "USD", // sandbox merchant is US/USD
    monthlyBudget: num(d.monthlyBudget, "monthlyBudget", 100_000),
    perItemLimit: num(d.perItemLimit, "perItemLimit", 100_000),
    allowedCategories: [...new Set(cats as string[])],
    askBeforeNewStore: bool(d.askBeforeNewStore, "askBeforeNewStore"),
    onlyShipToSavedPlaces: bool(d.onlyShipToSavedPlaces, "onlyShipToSavedPlaces"),
    askIfUnsureAboutSize: bool(d.askIfUnsureAboutSize, "askIfUnsureAboutSize"),
  };
  await userDoc(uid).collection("settings").doc("rules").set({ ...rules, updatedAt: FieldValue.serverTimestamp() });
  return rules;
});
