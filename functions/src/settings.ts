import { HttpsError, onCall } from "firebase-functions/v2/https";
import { ENFORCE_APP_CHECK } from "./options.js";
import { FieldValue } from "firebase-admin/firestore";
import { userDoc } from "./lib/db.js";
import { profileRef, requireUid } from "./purchase/purchase.js";
import type { HouseRules, Profile } from "./rules.js";

const num = (v: unknown, name: string, max: number) => {
  if (typeof v !== "number" || !Number.isFinite(v) || v < 0 || v > max) throw new HttpsError("invalid-argument", `${name} must be 0–${max}.`);
  return Math.round(v * 100) / 100;
};
const bool = (v: unknown, name: string) => {
  if (typeof v !== "boolean") throw new HttpsError("invalid-argument", `${name} must be true or false.`);
  return v;
};

/** Save the user's house rules (the app's Set house rules screen). Clients read them straight from Firestore. */
export const setRules = onCall<Partial<HouseRules>>({ cors: true, enforceAppCheck: ENFORCE_APP_CHECK }, async (request) => {
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
    // Older app builds don't send it: keep what's saved.
    giftBudget: d.giftBudget === undefined
      ? (((await userDoc(uid).collection("settings").doc("rules").get()).data()?.giftBudget as number | undefined) ?? 0)
      : num(d.giftBudget, "giftBudget", 100_000),
  };
  await userDoc(uid).collection("settings").doc("rules").set({ ...rules, updatedAt: FieldValue.serverTimestamp() });
  // Ask-if-unsure lives in the profile too (the Sizes screen sets it); keep the two the same.
  await profileRef(uid).set({ askIfUnsure: rules.askIfUnsureAboutSize, updatedAt: FieldValue.serverTimestamp() }, { merge: true });
  return rules;
});

const SIZE_PATTERNS = {
  top: /^(XXS|XS|S|M|L|XL|XXL|XXXL)$/,
  waist: /^(2[4-9]|3\d|4[0-8])$/,
  shoe: /^(US|UK|EU) \d{1,2}(\.5)?$/,
  fit: /^(Slim|Regular|Relaxed)$/,
} as const;

/**
 * Sizes, fit, the fetcher's name and ask-if-unsure (the Sizes and "Meet your fetcher" screens). Only the fields
 * sent are changed; null clears a size. The rules engine reads these for the size rule. Clients read
 * users/{uid}/profile/main straight from Firestore.
 */
export const setProfile = onCall<{ sizes?: Record<string, string | null>; askIfUnsure?: boolean; dogName?: string }>(
  { cors: true, enforceAppCheck: ENFORCE_APP_CHECK },
  async (request) => {
    const uid = requireUid(request.auth?.uid);
    const d = request.data ?? {};
    const update: Record<string, unknown> = {};
    if (d.sizes !== undefined) {
      if (!d.sizes || typeof d.sizes !== "object") throw new HttpsError("invalid-argument", "sizes must be an object.");
      for (const [key, value] of Object.entries(d.sizes)) {
        const pattern = SIZE_PATTERNS[key as keyof typeof SIZE_PATTERNS];
        if (!pattern) throw new HttpsError("invalid-argument", `Unknown size "${key}" (top, waist, shoe, fit).`);
        if (value !== null && (typeof value !== "string" || !pattern.test(value))) {
          throw new HttpsError("invalid-argument", `sizes.${key} "${value}" isn't a size I know.`);
        }
        update[`sizes.${key}`] = value;
      }
    }
    if (d.askIfUnsure !== undefined) update.askIfUnsure = bool(d.askIfUnsure, "askIfUnsure");
    if (d.dogName !== undefined) {
      const name = typeof d.dogName === "string" ? d.dogName.trim() : "";
      if (!/^[\p{L}\p{N}' .-]{1,20}$/u.test(name)) throw new HttpsError("invalid-argument", "dogName must be 1–20 letters.");
      update.dogName = name;
    }
    if (!Object.keys(update).length) throw new HttpsError("invalid-argument", "Nothing to save.");

    const ref = profileRef(uid);
    // update() with dotted paths needs the doc to exist; set(merge) with nested objects would replace sizes.
    await ref.set({ updatedAt: FieldValue.serverTimestamp() }, { merge: true });
    await ref.update(update);
    if (typeof update.askIfUnsure === "boolean") {
      const rules = userDoc(uid).collection("settings").doc("rules");
      if ((await rules.get()).exists) await rules.update({ askIfUnsureAboutSize: update.askIfUnsure });
    }
    return (await ref.get()).data() as Profile;
  },
);
