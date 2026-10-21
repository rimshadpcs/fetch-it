import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { CHANNEL3_API_KEY, FETCH_SIGNING_KEY, GEMINI_API_KEY } from "../config.js";
import type { DetectedItem } from "../lib/gemini.js";
import { InvalidToken, sha256, sign, TOKEN_TTL_MS, verify, type ItemsClaim, type OfferClaim } from "../lib/sign.js";
import { runFetch, type FetchEvent, type FetchResult, type RankedOffer } from "./pipeline.js";

interface FetchItemRequest {
  /** JPEG, base64 without a data: prefix, ideally already resized to ~1024 px on the device. */
  imageBase64: string;
  /** After a `choose` response: which item to search. */
  itemIndex?: number;
  /** After a `choose` response: its `itemsToken` (same photo), so the server can skip Gemini. */
  itemsToken?: string;
}

/** What the app gets: the pipeline result plus signed tokens for the next step. */
export type FetchItemResponse = FetchResult & {
  /** Send back with `itemIndex` (and the same photo) to search one of these items. */
  itemsToken: string;
  offers?: SignedOffer[];
};

const MAX_BYTES = 8 * 1024 * 1024;

export type SignedOffer = RankedOffer & { offerId: string };

/** Attach a signed offerId to each offer: product, store, colour, bound to the user. Never a price. */
export function signOffers(key: string, uid: string, offers: RankedOffer[]): SignedOffer[] {
  const exp = Date.now() + TOKEN_TTL_MS;
  return offers.map((o) => ({
    ...o,
    offerId: sign(key, {
      kind: "offer",
      uid,
      productId: o.productId,
      merchant: o.merchant,
      color: o.color && o.colorOption ? { option: o.colorOption, label: o.color } : null,
      title: o.title,
      imageUrl: o.imageUrl,
      exp,
    } satisfies OfferClaim),
  }));
}

/**
 * Photo in, offers out. The photo is only held in memory for this request: it's never written to Storage,
 * Firestore or logs, and only a crop of the chosen item is sent to Channel3.
 */
export const fetchItem = onCall<FetchItemRequest, Promise<FetchItemResponse>, FetchEvent>(
  {
    secrets: [GEMINI_API_KEY, CHANNEL3_API_KEY, FETCH_SIGNING_KEY],
    memory: "1GiB",
    timeoutSeconds: 60,
    cors: true,
  },
  async (request, response) => {
    const uid = request.auth?.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in to fetch.");

    const { imageBase64, itemIndex, itemsToken } = request.data ?? {};
    if (typeof imageBase64 !== "string" || !imageBase64) throw new HttpsError("invalid-argument", "imageBase64 is required.");
    const image = Buffer.from(imageBase64, "base64");
    if (!image.length || image.length > MAX_BYTES) throw new HttpsError("invalid-argument", "Image is empty or over 8 MB.");
    if (itemIndex !== undefined && (!Number.isInteger(itemIndex) || itemIndex < 0)) {
      throw new HttpsError("invalid-argument", "itemIndex must be a non-negative integer.");
    }

    const key = FETCH_SIGNING_KEY.value();
    const photo = sha256(image);
    let items: DetectedItem[] | undefined;
    if (itemsToken !== undefined) {
      try {
        const claim = verify<ItemsClaim<DetectedItem>>(key, itemsToken);
        if (claim.kind !== "items" || claim.uid !== uid) throw new InvalidToken("wrong token");
        if (claim.photo !== photo) throw new InvalidToken("itemsToken is for a different photo");
        if (claim.exp < Date.now()) throw new InvalidToken("itemsToken expired");
        items = claim.items;
      } catch (e) {
        if (e instanceof InvalidToken) throw new HttpsError("invalid-argument", `itemsToken rejected: ${e.message}`);
        throw e;
      }
      if (itemIndex !== undefined && itemIndex >= items.length) {
        throw new HttpsError("invalid-argument", `itemIndex must be below ${items.length}.`);
      }
    }

    let result: FetchResult;
    try {
      result = await runFetch(image, {
        geminiKey: GEMINI_API_KEY.value(),
        channel3Key: CHANNEL3_API_KEY.value(),
        userId: uid,
        itemIndex,
        items,
        // Streaming clients get items, then image matches, then offers. No-op otherwise.
        emit: (e) => void response?.sendChunk(e),
      });
    } catch (e) {
      if (e instanceof RangeError) throw new HttpsError("invalid-argument", e.message);
      logger.error("fetchItem failed", e);
      throw new HttpsError("unavailable", "Couldn't fetch that right now. Try again.");
    }

    const exp = Date.now() + TOKEN_TTL_MS;
    const signedItems = sign(key, { kind: "items", uid, photo, items: result.items, exp } satisfies ItemsClaim);
    logger.info("fetchItem", {
      uid,
      status: result.status,
      items: result.items.length,
      offers: result.status === "results" ? result.offers.length : 0,
      timings: result.timings,
    });
    if (result.status !== "results") return { ...result, itemsToken: signedItems };
    return {
      ...result,
      itemsToken: signedItems,
      offers: signOffers(key, uid, result.offers),
    };
  },
);
