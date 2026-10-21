import { createHash, createHmac, timingSafeEqual } from "node:crypto";

// Signed tokens let the app hold data between calls (the items found in a photo, an offer to buy)
// without the server storing anything, and without trusting what the app sends back.
// Format: base64url(JSON payload) + "." + base64url(HMAC-SHA256). Each token names its purpose and user.

const b64url = (b: Buffer) => b.toString("base64url");
const mac = (key: string, data: string) => createHmac("sha256", key).update(data).digest();

export class InvalidToken extends Error {}

export function sign(key: string, payload: object): string {
  const body = b64url(Buffer.from(JSON.stringify(payload)));
  return `${body}.${b64url(mac(key, body))}`;
}

export function verify<T>(key: string, token: unknown): T {
  if (typeof token !== "string" || !token.includes(".")) throw new InvalidToken("malformed token");
  const [body, sig] = token.split(".", 2);
  const expected = mac(key, body);
  const given = Buffer.from(sig, "base64url");
  if (given.length !== expected.length || !timingSafeEqual(given, expected)) throw new InvalidToken("bad signature");
  return JSON.parse(Buffer.from(body, "base64url").toString()) as T;
}

export const sha256 = (data: Buffer | string) => createHash("sha256").update(data).digest("base64url");

/** Items from fetchItem, bound to the user and the exact photo bytes they came from. */
export interface ItemsClaim<Item = unknown> {
  kind: "items";
  uid: string;
  /** sha256 of the photo bytes, so the items can't be replayed against another photo. */
  photo: string;
  items: Item[];
  exp: number;
}

/** An offer the user can buy: which product, at which store, in which colour. Prices are never in here. */
export interface OfferClaim {
  kind: "offer";
  uid: string;
  productId: string;
  merchant: string;
  color: { option: string; label: string } | null;
  title: string;
  imageUrl: string | null;
  exp: number;
}

export const TOKEN_TTL_MS = 24 * 60 * 60 * 1000;
