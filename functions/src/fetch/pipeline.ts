import { Channel3, mainImage, type C3Offer, type C3Product } from "../lib/channel3.js";
import { identify, type IdentifiedItem } from "../lib/gemini.js";
import { prepareImages } from "../lib/image.js";
import { filterByCategory, mergeAndDedupe, targetCategories, type Candidate } from "./rank.js";

const DETAIL_COUNT = 5;

export interface SizeOption {
  label: string;
  available: string | null;
}
export interface ColorOption {
  /** Named option from the product's variants ("Black"); null for colourways listed as separate products. */
  label: string | null;
  /** Dominant photo colour, for a swatch when there's no label. */
  hex: string | null;
  productId: string | null;
  available: string | null;
  imageUrl: string | null;
}
export interface RankedOffer {
  productId: string;
  title: string;
  brand: string | null;
  merchant: string;
  url: string;
  imageUrl: string | null;
  /** Always from product detail — search prices can be stale (Spike 1: $44.97 in search, $129.50 live). */
  price: number;
  compareAtPrice: number | null;
  currency: string;
  availability: string;
  sizes: SizeOption[] | null;
  colors: ColorOption[];
  matchedBy: string[];
  category: string | null;
}
export interface Timings {
  [step: string]: number;
}

/** Events in arrival order. The callable forwards them as stream chunks; the final result repeats the last ones. */
export type FetchEvent =
  | { type: "image_matches"; previews: { productId: string; title: string; merchant: string; imageUrl: string | null }[] }
  | { type: "identified"; item: IdentifiedItem; model: string }
  | { type: "offers"; offers: RankedOffer[] };

export interface FetchResult {
  item: IdentifiedItem;
  model: string;
  offers: RankedOffer[];
  timings: Timings;
}

interface Deps {
  geminiKey: string;
  channel3Key: string;
  userId: string;
  emit?: (e: FetchEvent) => void;
}

export async function runFetch(image: Buffer, { geminiKey, channel3Key, userId, emit = () => {} }: Deps): Promise<FetchResult> {
  const start = performance.now();
  const timings: Timings = {};
  const timed = async <T>(step: string, fn: () => Promise<T>): Promise<T> => {
    const t = performance.now();
    try {
      return await fn();
    } finally {
      timings[step] = Math.round(performance.now() - t);
    }
  };
  const c3 = new Channel3(channel3Key, userId);

  // Product detail is free but Channel3 serves parallel calls slowly (5 at once ≈ 1.5 s vs 0.35 s for one),
  // so we start likely winners early and reuse the promise.
  const detailCache = new Map<string, Promise<C3Product | null>>();
  const detail = (id: string) => {
    if (!detailCache.has(id)) detailCache.set(id, c3.product(id).catch(() => null));
    return detailCache.get(id)!;
  };

  const images = await timed("resize", () => prepareImages(image));

  // Identify and image search are independent: run together, surface image matches as soon as they land.
  const imageSearch = timed("image_search", () => c3.imageSearch(images.search)).then((r) => {
    // Image search usually lands well before identify + text search; warm details for its top hits meanwhile.
    r.products.slice(0, DETAIL_COUNT).forEach((p) => void detail(p.id));
    emit({
      type: "image_matches",
      previews: r.products.slice(0, 8).map((p) => ({
        productId: p.id,
        title: p.title,
        merchant: p.offers[0]?.domain ?? "",
        imageUrl: mainImage(p),
      })),
    });
    return r;
  });
  const identified = timed("identify", () => identify(geminiKey, images.identify)).then((r) => {
    emit({ type: "identified", ...r });
    return r;
  });
  // Text search starts the moment identify returns, without waiting for image search.
  const textSearch = identified.then(({ item }) => timed("text_search", () => c3.textSearch(item.search_query)));

  const [{ item, model }, imageRes, textRes] = await Promise.all([identified, imageSearch, textSearch]);

  const target = targetCategories(item, textRes.products, imageRes.products);
  const candidates = mergeAndDedupe([
    ["image", filterByCategory(imageRes.products, target)],
    ["text", filterByCategory(textRes.products, target)],
  ]);

  const top = candidates.slice(0, DETAIL_COUNT);
  const prefetched = top.filter((c) => detailCache.has(c.product.id)).length;
  const details = await timed("product_detail", () => Promise.all(top.map((c) => detail(c.product.id))));
  timings.details_prefetched = prefetched;
  const offers = top
    .map((c, i) => details[i] && toOffer(c, details[i]))
    .filter((o): o is RankedOffer => o !== null);
  emit({ type: "offers", offers });

  timings.total = Math.round(performance.now() - start);
  return { item, model, offers, timings };
}

const sizeOption = (p: C3Product) => p.variants?.options.find((o) => /size/i.test(o.name));
const colorOption = (p: C3Product) => p.variants?.options.find((o) => /colou?r/i.test(o.name));

/** Cheapest in-stock offer, preferring the store we matched on. */
function pickOffer(detail: C3Product, merchant: string): C3Offer | null {
  const live = detail.offers.filter((o) => o.availability !== "OutOfStock" && o.price?.price != null);
  if (!live.length) return null;
  return live.find((o) => o.domain === merchant) ?? live.sort((a, b) => a.price.price - b.price.price)[0];
}

function toOffer(c: Candidate, detail: C3Product): RankedOffer | null {
  const offer = pickOffer(detail, c.merchant);
  if (!offer) return null;
  const colors: ColorOption[] = (colorOption(detail)?.values ?? [])
    .filter((v) => v.exists)
    .map((v) => ({
      label: v.label,
      hex: null,
      productId: v.product_id ?? null,
      available: v.available,
      imageUrl: v.thumbnail_url ?? null,
    }));
  // Colourways Channel3 lists as separate products at the same store (same title), shown as photo swatches.
  if (c.colorways.length && !colors.length) {
    for (const p of [detail, ...c.colorways]) {
      colors.push({ label: null, hex: p.colors?.[0]?.hex ?? null, productId: p.id, available: null, imageUrl: mainImage(p) });
    }
  }
  return {
    productId: detail.id,
    title: detail.title,
    brand: detail.brands?.[0]?.name ?? null,
    merchant: offer.domain,
    url: offer.url,
    imageUrl: mainImage(detail),
    price: offer.price.price,
    compareAtPrice: offer.price.compare_at_price,
    currency: offer.price.currency,
    availability: offer.availability,
    sizes: sizeOption(detail)?.values.filter((v) => v.exists).map((v) => ({ label: v.label, available: v.available })) ?? null,
    colors,
    matchedBy: [...c.sources],
    category: detail.category?.slug ?? null,
  };
}
