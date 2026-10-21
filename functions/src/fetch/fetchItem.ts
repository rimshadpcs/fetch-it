import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { CHANNEL3_API_KEY, GEMINI_API_KEY } from "../config.js";
import { runFetch, type FetchEvent, type FetchResult } from "./pipeline.js";

interface FetchItemRequest {
  /** JPEG, base64 without a data: prefix, ideally already resized to ~1024 px on the device. */
  imageBase64: string;
}

const MAX_BYTES = 8 * 1024 * 1024;

export const fetchItem = onCall<FetchItemRequest, Promise<FetchResult>, FetchEvent>(
  { secrets: [GEMINI_API_KEY, CHANNEL3_API_KEY], memory: "1GiB", timeoutSeconds: 60, cors: true },
  async (request, response) => {
    const uid = request.auth?.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in to fetch.");

    const b64 = request.data?.imageBase64;
    if (typeof b64 !== "string" || !b64) throw new HttpsError("invalid-argument", "imageBase64 is required.");
    const image = Buffer.from(b64, "base64");
    if (!image.length || image.length > MAX_BYTES) throw new HttpsError("invalid-argument", "Image is empty or over 8 MB.");

    try {
      const result = await runFetch(image, {
        geminiKey: GEMINI_API_KEY.value(),
        channel3Key: CHANNEL3_API_KEY.value(),
        userId: uid,
        // Streaming clients get image matches first, then the identified item, then offers. No-op otherwise.
        emit: (e) => void response?.sendChunk(e),
      });
      logger.info("fetchItem", { uid, model: result.model, offers: result.offers.length, timings: result.timings });
      return result;
    } catch (e) {
      logger.error("fetchItem failed", e);
      throw new HttpsError("unavailable", "Couldn't fetch that right now. Try again.");
    }
  },
);
