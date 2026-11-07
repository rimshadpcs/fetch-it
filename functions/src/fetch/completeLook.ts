// "Goes well with": after a buy, Scout suggests up to 3 things that go with it (Gemini picks the kinds of item,
// Channel3 finds them), each with 2 live-priced offers that buy through the normal purchase path (house rules apply).
// Cached on the purchase for 20 h so reopening the order doesn't spend credits again; offers are re-signed per call.

import { GoogleGenAI } from "@google/genai";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { ENFORCE_APP_CHECK } from "../options.js";
import { CHANNEL3_API_KEY, FETCH_SIGNING_KEY, GEMINI_API_KEY } from "../config.js";
import { rateLimit, requireServices } from "../limits.js";
import { Channel3, type C3Filters } from "../lib/channel3.js";
import { meter } from "../lib/meter.js";
import { FLASH_LITE } from "../lib/gemini.js";
import { userDoc } from "../lib/db.js";
import { requireUid, type PurchaseDoc } from "../purchase/purchase.js";
import { signOffers, type SignedOffer } from "./fetchItem.js";
import { OfferHydrator, type RankedOffer } from "./pipeline.js";
import { mergeAndDedupe, normalizeTitle } from "./rank.js";

const CACHE_MS = 20 * 3600_000;
const PER_GROUP = 2;

export interface LookIdea {
  label: string;
  query: string;
  why: string;
}

const SCHEMA = {
  type: "object",
  properties: {
    ideas: {
      type: "array",
      maxItems: 3,
      items: {
        type: "object",
        properties: {
          label: { type: "string", description: "2–3 words, lowercase, e.g. 'white sneakers'" },
          query: { type: "string", description: "a shopping search query for one product, incl. men's/women's if it matters" },
          why: { type: "string", description: "at most 8 words, why it goes with the bought item" },
        },
        required: ["label", "query", "why"],
      },
    },
  },
  required: ["ideas"],
};

/** Clean what the model returned: at most 3, short, no repeats, nothing that's the same kind of item. */
export function cleanIdeas(raw: unknown, boughtTitle: string): LookIdea[] {
  const list = (raw as { ideas?: LookIdea[] } | null)?.ideas ?? [];
  const seen = new Set<string>();
  const bought = normalizeTitle(boughtTitle);
  return list
    .filter((i) => typeof i?.label === "string" && typeof i?.query === "string" && i.label.trim() && i.query.trim())
    .map((i) => ({ label: i.label.trim().toLowerCase().slice(0, 30), query: i.query.trim().slice(0, 120), why: (i.why ?? "").trim().slice(0, 80) }))
    .filter((i) => {
      const k = i.label.replace(/s$/, "");
      if (seen.has(k) || bought.includes(k)) return false;
      seen.add(k);
      return true;
    })
    .slice(0, 3);
}

/** Things that go with a $43 lamp shouldn't cost $799: keep offers up to 3× the item (at least $100). */
export function affordable<T extends { price: number }>(offers: T[], boughtPrice: number): T[] {
  const cap = Math.max(boughtPrice * 3, 100);
  const kept = offers.filter((o) => o.price <= cap);
  return kept.length > 0 ? kept : [...offers].sort((a, b) => a.price - b.price).slice(0, 1);
}

interface CachedLook {
  generatedAt: Timestamp;
  groups: { label: string; why: string; offers: RankedOffer[] }[];
}

export const completeLook = onCall<{ purchaseId: string }>(
  { secrets: [GEMINI_API_KEY, CHANNEL3_API_KEY, FETCH_SIGNING_KEY], timeoutSeconds: 45, memory: "512MiB", cors: true, enforceAppCheck: ENFORCE_APP_CHECK },
  async (request) => {
    const started = Date.now();
    const uid = requireUid(request.auth?.uid);
    const purchaseId = request.data?.purchaseId;
    if (typeof purchaseId !== "string" || !/^[A-Za-z0-9_-]{6,64}$/.test(purchaseId)) throw new HttpsError("invalid-argument", "purchaseId is required.");
    const ref = userDoc(uid).collection("purchases").doc(purchaseId);
    const snap = await ref.get();
    if (!snap.exists) throw new HttpsError("not-found", "No such purchase.");
    const p = snap.data() as PurchaseDoc & { look?: CachedLook };
    const sign = (groups: CachedLook["groups"]) =>
      groups.map((g) => ({ label: g.label, why: g.why, offers: signOffers(FETCH_SIGNING_KEY.value(), uid, g.offers) as SignedOffer[] }));

    if (p.look && Date.now() - p.look.generatedAt.toMillis() < CACHE_MS) return { groups: sign(p.look.groups), cached: true };

    await rateLimit(uid, "completeLook");
    await requireServices(uid, ["gemini", "channel3"]);
    const c3 = new Channel3(CHANNEL3_API_KEY.value(), uid);
    const source = await c3.product(p.productId, p.color ? { [p.color.option]: p.color.label } : {}, [p.merchant]).catch(() => null);
    const department = source?.gender === "male" ? "men's" : source?.gender === "female" ? "women's" : null;

    meter.record("gemini");
    const ai = new GoogleGenAI({ apiKey: GEMINI_API_KEY.value() });
    const res = await ai.models.generateContent({
      model: FLASH_LITE.model,
      contents:
        `Someone just bought: "${p.title}" (category: ${p.categoryPath.join(" > ") || "unknown"}` +
        `${department ? `, ${department}` : ""}${source?.age && source.age !== "adult" ? `, ${source.age}` : ""}, ${p.currency} ${p.price}). ` +
        `Suggest up to 3 different kinds of product that go well with it and that they'd plausibly buy next ` +
        `(for clothing: pieces that complete the outfit; for home items: things used or styled with it). ` +
        `Not another one of the same thing, not accessories for a different product, nothing generic like gift cards.`,
      config: { responseMimeType: "application/json", responseJsonSchema: SCHEMA, thinkingConfig: FLASH_LITE.thinking, abortSignal: AbortSignal.timeout(15_000) },
    });
    const ideas = cleanIdeas(JSON.parse(res.text ?? "{}"), p.title);
    if (ideas.length === 0) return { groups: [], cached: false };

    const filters: C3Filters = {
      ...(source?.age && { age: [source.age] }),
      ...((source?.gender === "male" || source?.gender === "female") && { gender: source.gender }),
    };
    const hydrator = new OfferHydrator(c3);
    const groups = (
      await Promise.all(
        ideas.map(async (idea) => {
          const found = await c3.textSearch(idea.query, filters, 8).catch(() => ({ products: [] }));
          const candidates = mergeAndDedupe([["text", found.products.filter((x) => x.id !== p.productId)]]);
          const { offers } = await hydrator.hydrate(candidates, [], PER_GROUP + 1);
          return { label: idea.label, why: idea.why, offers: affordable(offers, p.price ?? 0).slice(0, PER_GROUP) };
        }),
      )
    ).filter((g) => g.offers.length > 0);

    // Plain JSON for Firestore (no undefined fields).
    await ref.update({ look: { generatedAt: Timestamp.now(), groups: JSON.parse(JSON.stringify(groups)) }, updatedAt: FieldValue.serverTimestamp() });
    logger.info("completeLook", { uid, purchaseId, ideas: ideas.map((i) => i.label), offers: groups.map((g) => g.offers.length), ms: Date.now() - started });
    return { groups: sign(groups), cached: false };
  },
);
