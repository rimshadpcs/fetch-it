import { Channel3, mainImage } from "../lib/channel3.js";
import type { OfferClaim } from "../lib/sign.js";
import type { OfferForRules } from "../rules.js";
import { normalizeSize, sizeOptionOf } from "../fetch/variants.js";

export interface LiveOffer extends OfferForRules {
  productId: string;
  title: string;
  imageUrl: string | null;
  url: string | null;
  color: string | null;
}

/**
 * Re-fetch Channel3 product detail right before deciding: the price, stock and sizes the rules see are
 * live, never from search results or anything the app sent. Same product, same store, same colour.
 */
export async function fetchLiveOffer(c3: Channel3, claim: Pick<OfferClaim, "productId" | "merchant" | "color" | "title" | "imageUrl">): Promise<LiveOffer> {
  // Pin the store the user picked: never switch merchants (or prices) behind their back.
  const detail = await c3.product(claim.productId, claim.color ? { [claim.color.option]: claim.color.label } : {}, [claim.merchant]);
  const offer = detail.offers.find((o) => o.domain === claim.merchant) ?? null;
  const sizeOption = sizeOptionOf(detail.variants);
  return {
    productId: detail.id,
    title: detail.title ?? claim.title,
    imageUrl: mainImage(detail) ?? claim.imageUrl,
    url: offer?.url ?? null,
    color: claim.color?.label ?? null,
    merchant: claim.merchant,
    price: offer?.price.price ?? null,
    currency: offer?.price.currency ?? "USD",
    inStock: !!offer && offer.availability !== "OutOfStock" && offer.price?.price != null,
    categoryPath: detail.category?.path.map((c) => c.slug) ?? [],
    sizes: sizeOption
      ? sizeOption.values
          .filter((v) => v.exists)
          .map((v) => ({ label: v.label, size: normalizeSize(v.label), available: v.available }))
      : null,
  };
}
