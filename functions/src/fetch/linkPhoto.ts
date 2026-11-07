// "Share to Fetch It" with a link instead of a photo (Pinterest, a store page in Chrome): find the photo it points at
// and hand it back as a JPEG, which the app then fetches like any other photo.
//   Pinterest → Pinterest's public oEmbed endpoint (the pin's image), page preview as a fallback.
//   Other pages → the page's own preview image (og:image), fetched as "Fetch It", nothing disguised.
//   Instagram → refused with a hint: posts only expose their photo to logged-in users / Meta-approved apps.
// Every request goes to public hosts only (no private / metadata addresses, every redirect re-checked), with size and
// time limits. Nothing is stored.

import { lookup } from "node:dns/promises";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger } from "firebase-functions";
import { ENFORCE_APP_CHECK } from "../options.js";
import { prepareImage } from "../lib/image.js";
import { rateLimit } from "../limits.js";
import { biggerPin, classify, firstUrl, isPinterest, isPrivateAddress, previewImage } from "./link.js";

const UA = "FetchIt/1.0 (link preview for a shopping app)";
const PAGE_MAX = 3_000_000;
const IMAGE_MAX = 10_000_000;
const TIMEOUT_MS = 8_000;

async function assertPublic(u: URL) {
  if (u.protocol !== "https:" && u.protocol !== "http:") throw new HttpsError("invalid-argument", "That link isn't a web page.");
  if (u.port && u.port !== "443" && u.port !== "80") throw new HttpsError("invalid-argument", "That link isn't a web page.");
  if (u.username || u.password) throw new HttpsError("invalid-argument", "That link isn't a web page.");
  const addrs = await lookup(u.hostname, { all: true }).catch(() => []);
  if (addrs.length === 0 || addrs.some((a) => isPrivateAddress(a.address))) {
    throw new HttpsError("invalid-argument", "I can't open that link.");
  }
}

/** GET with redirects followed by hand (each hop checked), a body cap and a timeout. */
async function get(start: URL, max: number, accept: string): Promise<{ url: URL; type: string; body: Buffer }> {
  let url = start;
  for (let hop = 0; hop < 6; hop++) {
    await assertPublic(url);
    const res = await fetch(url, { redirect: "manual", headers: { "User-Agent": UA, Accept: accept }, signal: AbortSignal.timeout(TIMEOUT_MS) });
    if (res.status >= 300 && res.status < 400 && res.headers.get("location")) {
      url = new URL(res.headers.get("location")!, url);
      await res.body?.cancel();
      continue;
    }
    if (!res.ok || !res.body) throw new HttpsError("not-found", "That link didn't open for me. Try sharing a screenshot instead.");
    const chunks: Buffer[] = [];
    let size = 0;
    for await (const chunk of res.body as unknown as AsyncIterable<Uint8Array>) {
      size += chunk.length;
      if (size > max) throw new HttpsError("invalid-argument", "That's too big for me to fetch. Try a screenshot instead.");
      chunks.push(Buffer.from(chunk));
    }
    return { url, type: res.headers.get("content-type") ?? "", body: Buffer.concat(chunks) };
  }
  throw new HttpsError("not-found", "That link kept redirecting. Try sharing a screenshot instead.");
}

async function pinImage(link: URL): Promise<URL | null> {
  const k = classify(link);
  let pinId = k.kind === "pinterest" ? k.pinId : null;
  let page: { url: URL; body: Buffer } | null = null;
  if (!pinId) {
    // pin.it short links: follow them to the pin.
    page = await get(link, PAGE_MAX, "text/html");
    if (!isPinterest(page.url)) return previewImage(page.body.toString("utf8"), page.url);
    pinId = page.url.pathname.match(/\/pin\/(\d{5,})/)?.[1] ?? null;
  }
  if (pinId) {
    const oembed = new URL("https://www.pinterest.com/oembed.json");
    oembed.searchParams.set("url", `https://www.pinterest.com/pin/${pinId}/`);
    const r = await get(oembed, 100_000, "application/json").catch(() => null);
    const thumb = r ? (JSON.parse(r.body.toString("utf8")) as { thumbnail_url?: string }).thumbnail_url : undefined;
    if (thumb) return new URL(biggerPin(thumb));
  }
  page ??= await get(link, PAGE_MAX, "text/html");
  return previewImage(page.body.toString("utf8"), page.url);
}

export const linkPhoto = onCall<{ text: string }>(
  { memory: "512MiB", timeoutSeconds: 30, cors: true, enforceAppCheck: ENFORCE_APP_CHECK },
  async (request) => {
    const uid = request.auth?.uid;
    if (!uid) throw new HttpsError("unauthenticated", "Sign in to fetch.");
    const text = request.data?.text;
    if (typeof text !== "string" || text.length > 4000) throw new HttpsError("invalid-argument", "text is required.");
    const link = firstUrl(text);
    if (!link) throw new HttpsError("invalid-argument", "There's no link in that. Share a photo or a screenshot instead.");
    const kind = classify(link).kind;
    if (kind === "instagram") {
      throw new HttpsError("failed-precondition", "Instagram keeps the photo to itself. Screenshot the post and share that instead.");
    }
    await rateLimit(uid, "linkPhoto");

    const started = Date.now();
    const imageUrl = kind === "pinterest" ? await pinImage(link) : previewImage((await get(link, PAGE_MAX, "text/html")).body.toString("utf8"), link);
    if (!imageUrl) throw new HttpsError("not-found", "I couldn't find a photo on that page. Try sharing a screenshot instead.");
    const img = await get(imageUrl, IMAGE_MAX, "image/*");
    if (!img.type.startsWith("image/")) throw new HttpsError("not-found", "I couldn't find a photo on that page. Try sharing a screenshot instead.");
    const jpeg = await prepareImage(img.body).catch(() => {
      throw new HttpsError("not-found", "I couldn't read the photo on that page. Try sharing a screenshot instead.");
    });
    logger.info("link photo", { uid, kind, host: link.hostname, imageHost: imageUrl.hostname, width: jpeg.width, height: jpeg.height, ms: Date.now() - started });
    return { imageBase64: jpeg.jpeg.toString("base64"), source: kind === "pinterest" ? "Pinterest" : link.hostname.replace(/^www\./, "") };
  },
);
