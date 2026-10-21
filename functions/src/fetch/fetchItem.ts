import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { CHANNEL3_API_KEY, GEMINI_API_KEY } from "../config.js";
import type { DetectedItem } from "../lib/gemini.js";
import { runFetch, type FetchEvent, type FetchResult } from "./pipeline.js";

interface FetchItemRequest {
  /** JPEG, base64 without a data: prefix, ideally already resized to ~1024 px on the device. */
  imageBase64: string;
  /** After a `choose` response: which item to search. */
  itemIndex?: number;
  /** After a `choose` response: send its `items` back so the server doesn't re-run Gemini. */
  items?: DetectedItem[];
}

const MAX_BYTES = 8 * 1024 * 1024;

const isItem = (i: unknown): i is DetectedItem => {
  const x = i as DetectedItem;
  const b = x?.box;
  return (
    typeof x?.search_query === "string" &&
    typeof x.item_type === "string" &&
    Array.isArray(x.colors) &&
    [b?.x, b?.y, b?.width, b?.height].every((n) => typeof n === "number" && n >= 0 && n <= 1)
  );
};

/**
 * Photo in, offers out. The photo is only held in memory for this request: it's never written to Storage,
 * Firestore or logs, and only a crop of the chosen item is sent to Channel3.
 */
export const fetchItem = onCall<FetchItemRequest, Promise<FetchResult>, FetchEvent>(
  { secrets: [GEMINI_API_KEY, CHANNEL3_API_KEY], memory: "1GiB", timeoutSeconds: 60, cors: true },
  async (request, response) => {
    const uid = request.auth?.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in to fetch.");

    const { imageBase64, itemIndex, items } = request.data ?? {};
    if (typeof imageBase64 !== "string" || !imageBase64) throw new HttpsError("invalid-argument", "imageBase64 is required.");
    const image = Buffer.from(imageBase64, "base64");
    if (!image.length || image.length > MAX_BYTES) throw new HttpsError("invalid-argument", "Image is empty or over 8 MB.");
    if (itemIndex !== undefined && (!Number.isInteger(itemIndex) || itemIndex < 0)) {
      throw new HttpsError("invalid-argument", "itemIndex must be a non-negative integer.");
    }
    if (items !== undefined && (!Array.isArray(items) || items.length > 10 || !items.every(isItem))) {
      throw new HttpsError("invalid-argument", "items must be the list returned by a previous call.");
    }
    if (items && itemIndex !== undefined && itemIndex >= items.length) {
      throw new HttpsError("invalid-argument", `itemIndex must be below ${items.length}.`);
    }

    try {
      const result = await runFetch(image, {
        geminiKey: GEMINI_API_KEY.value(),
        channel3Key: CHANNEL3_API_KEY.value(),
        userId: uid,
        itemIndex,
        items,
        // Streaming clients get items, then image matches, then offers. No-op otherwise.
        emit: (e) => void response?.sendChunk(e),
      });
      logger.info("fetchItem", {
        uid,
        status: result.status,
        items: result.items.length,
        offers: result.status === "results" ? result.offers.length : 0,
        timings: result.timings,
      });
      return result;
    } catch (e) {
      if (e instanceof RangeError) throw new HttpsError("invalid-argument", e.message);
      logger.error("fetchItem failed", e);
      throw new HttpsError("unavailable", "Couldn't fetch that right now. Try again.");
    }
  },
);
