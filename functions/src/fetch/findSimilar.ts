import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { CHANNEL3_API_KEY, FETCH_SIGNING_KEY } from "../config.js";
import { Channel3, type C3Filters } from "../lib/channel3.js";
import { requireUid, verifyOffer } from "../purchase/purchase.js";
import { signOffers, type SignedOffer } from "./fetchItem.js";
import { OfferHydrator } from "./pipeline.js";
import { mergeAndDedupe, normalizeTitle, languageFactor } from "./rank.js";

const MIN_RESULTS = 5;

/**
 * "More like this" (no maxPrice) and "Find cheaper" (maxPrice = the current price) for an offer from
 * fetchItem. Same category, age and department as the source, same dedupe and live-detail prices as fetchItem.
 */
export const findSimilar = onCall<{ offerId: string; maxPrice?: number }>(
  { secrets: [CHANNEL3_API_KEY, FETCH_SIGNING_KEY], timeoutSeconds: 30, cors: true },
  async (request) => {
    const start = performance.now();
    const uid = requireUid(request.auth?.uid);
    const { offerId, maxPrice } = request.data ?? {};
    if (maxPrice !== undefined && (typeof maxPrice !== "number" || !(maxPrice > 0))) {
      throw new HttpsError("invalid-argument", "maxPrice must be a positive number.");
    }
    const claim = verifyOffer(offerId, uid);
    const c3 = new Channel3(CHANNEL3_API_KEY.value(), uid);

    // The source as it is now, at the store the user saw it: its category, age, department, live price.
    const source = await c3.product(claim.productId, claim.color ? { [claim.color.option]: claim.color.label } : {}, [claim.merchant]);
    const sourcePrice = source.offers.find((o) => o.domain === claim.merchant)?.price ?? null;
    const base: C3Filters = {
      ...(source.age && { age: [source.age] }),
      // Only a clear men's/women's department: Channel3's gender filter also drops unisex products.
      ...((source.gender === "male" || source.gender === "female") && { gender: source.gender }),
      ...(maxPrice !== undefined && { price: { max_price: maxPrice } }),
    };

    // Same leaf category first ("bedside-lamps"); widen to its parent ("lamps") if that's too thin.
    const path = source.category?.path ?? [];
    const scopes = [path.at(-1)?.slug, path.at(-2)?.slug].filter((s): s is string => !!s);
    let products = (await c3.similar(claim.productId, { ...base, ...(scopes[0] && { category_ids: [scopes[0]] }) }, 20)).products;
    let scope = scopes[0] ?? null;
    if (products.length < MIN_RESULTS && scopes[1]) {
      products = (await c3.similar(claim.productId, { ...base, category_ids: [scopes[1]] }, 20)).products;
      scope = scopes[1];
    }

    // Not the source itself, nor another colourway of the same listing.
    const sourceKey = `${normalizeTitle(source.title)}|${claim.merchant}`;
    const candidates = mergeAndDedupe([["similar", products.filter((p) => p.id !== claim.productId)]]).filter(
      (c) => `${normalizeTitle(c.product.title)}|${c.merchant}` !== sourceKey,
    );

    const hydrator = new OfferHydrator(c3);
    const { offers, dropped } = await hydrator.hydrate(candidates, claim.color ? [claim.color.label] : []);
    // maxPrice is enforced on the live detail price, never the price in the similar results.
    const kept = (maxPrice === undefined ? offers : offers.filter((o) => o.price <= maxPrice + 1e-9))
      // Stable: similarity order otherwise; non-English titles go last.
      .sort((a, b) => languageFactor(b.title) - languageFactor(a.title));

    const timingMs = Math.round(performance.now() - start);
    logger.info("findSimilar", { uid, productId: claim.productId, scope, candidates: candidates.length, offers: kept.length, timingMs });
    const result: {
      source: { productId: string; title: string; merchant: string; price: number | null; currency: string | null };
      category: string | null;
      offers: SignedOffer[];
      dropped: number;
      timingMs: number;
    } = {
      source: {
        productId: source.id,
        title: source.title,
        merchant: claim.merchant,
        price: sourcePrice?.price ?? null,
        currency: sourcePrice?.currency ?? null,
      },
      category: scope,
      offers: signOffers(FETCH_SIGNING_KEY.value(), uid, kept),
      dropped: dropped + (offers.length - kept.length),
      timingMs,
    };
    return result;
  },
);
