import { Channel3, mainImage, type C3Filters, type C3Offer, type C3Product } from "../lib/channel3.js";
import { identify, type DetectedItem } from "../lib/gemini.js";
import { cropForSearch, prepareImage } from "../lib/image.js";
import { filterByCategory, mergeAndDedupe, targetCategories, type Candidate } from "./rank.js";
import { buyableSizes, chooseColor, colorOptionOf, normalizeSize, sizeOptionOf } from "./variants.js";

const DETAIL_COUNT = 5;
/** Detail waves: the top 5, then one backfill wave for offers dropped as sold out in the matching colour. */
const MAX_WAVES = 2;

export interface SizeOption {
  /** As the store writes it ("SML", "XS 2-4"). */
  label: string;
  /** Canonical ("S", "XS", "1X", "32") for matching the user's saved sizes; null when unrecognised. */
  size: string | null;
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
  /** The colour this offer's price, sizes and image describe. */
  selected: boolean;
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
  /** The colour we picked to match the photo (or the store default). */
  color: string | null;
  /** Sizes that exist in `color` and aren't sold out; null when the product has no size option. */
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
  | { type: "items"; items: DetectedItem[]; model: string }
  | { type: "image_matches"; previews: { productId: string; title: string; merchant: string; imageUrl: string | null }[] }
  | { type: "offers"; offers: RankedOffer[] };

/**
 * - `results`: one item searched (the only one in the photo, or the one the user picked).
 * - `choose`: several items; show "Which one?" and call again with `itemIndex` and these `items`.
 * - `none`: nothing purchasable found.
 */
export type FetchResult =
  | { status: "results"; items: DetectedItem[]; itemIndex: number; model: string | null; offers: RankedOffer[]; timings: Timings }
  | { status: "choose" | "none"; items: DetectedItem[]; model: string | null; timings: Timings };

interface Deps {
  geminiKey: string;
  channel3Key: string;
  userId: string;
  /** Which of `items` to search. Required when the photo has several items. */
  itemIndex?: number;
  /** Items from an earlier `choose` response for this same photo; skips Gemini on the second call. */
  items?: DetectedItem[];
  emit?: (e: FetchEvent) => void;
}

export async function runFetch(
  image: Buffer,
  { geminiKey, channel3Key, userId, itemIndex, items: knownItems, emit = () => {} }: Deps,
): Promise<FetchResult> {
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
  const detail = (id: string, options: Record<string, string> = {}) => {
    const key = `${id}|${JSON.stringify(options)}`;
    if (!detailCache.has(key)) detailCache.set(key, c3.product(id, options).catch(() => null));
    return detailCache.get(key)!;
  };
  /**
   * Detail in the colour that matches the photo. Search results already list the colour options, so we can
   * usually ask for the right colour in one call; a prefetched default-colour detail is reused when it matches.
   */
  const detailInColor = async (p: C3Product, wanted: string[]) => {
    const option = colorOptionOf(p.variants);
    const color = chooseColor(p.variants, wanted);
    const plainKey = `${p.id}|{}`;
    if (!option || !color) return detail(p.id);
    if (detailCache.has(plainKey)) {
      const plain = await detailCache.get(plainKey)!;
      const selected = plain?.variants?.selected.find((s) => s.name === option.name)?.label;
      if (plain && selected === color) return plain;
    }
    return detail(p.id, { [option.name]: color });
  };

  const img = await timed("resize", () => prepareImage(image));

  let items = knownItems;
  let model: string | null = null;
  if (!items) {
    ({ items, model } = await timed("identify", () => identify(geminiKey, img.jpeg)));
    emit({ type: "items", items, model });
  }
  const done = (status: "choose" | "none"): FetchResult => {
    timings.total = Math.round(performance.now() - start);
    return { status, items: items!, model, timings };
  };
  if (!items.length) return done("none");
  if (itemIndex === undefined && items.length > 1) return done("choose");
  const index = itemIndex ?? 0;
  const item = items[index];
  if (!item) throw new RangeError(`itemIndex ${index} is out of range (0-${items.length - 1})`);

  // Only the chosen item goes to image search, so a busy background or other clothes don't steer the matches.
  const crop = await timed("crop", () => cropForSearch(img, item.box));
  const filters: C3Filters = {
    ...(item.age && { age: [item.age] }),
    // Only a clear men's/women's call: Channel3's gender filter also drops unisex products.
    ...((item.gender === "male" || item.gender === "female") && { gender: item.gender }),
  };
  const imageSearch = timed("image_search", () => c3.imageSearch(crop, filters)).then((r) => {
    // Image search usually lands before text search; warm details for its top hits meanwhile.
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
  const textSearch = timed("text_search", () => c3.textSearch(item.search_query, filters));
  const [imageRes, textRes] = await Promise.all([imageSearch, textSearch]);

  const target = targetCategories(item, textRes.products, imageRes.products);
  const candidates = mergeAndDedupe([
    ["image", filterByCategory(imageRes.products, target)],
    ["text", filterByCategory(textRes.products, target)],
  ]);

  const offers: RankedOffer[] = [];
  let dropped = 0;
  await timed("product_detail", async () => {
    let next = 0;
    for (let wave = 0; wave < MAX_WAVES && offers.length < DETAIL_COUNT && next < candidates.length; wave++) {
      const batch = candidates.slice(next, next + DETAIL_COUNT - offers.length);
      next += batch.length;
      const details = await Promise.all(batch.map((c) => detailInColor(c.product, item.colors)));
      batch.forEach((c, i) => {
        const offer = details[i] && toOffer(c, details[i]);
        if (offer) offers.push(offer);
        else dropped++;
      });
    }
  });
  timings.offers_dropped = dropped;
  emit({ type: "offers", offers });

  timings.total = Math.round(performance.now() - start);
  return { status: "results", items, itemIndex: index, model, offers, timings };
}

/** Cheapest in-stock offer, preferring the store we matched on. */
function pickOffer(detail: C3Product, merchant: string): C3Offer | null {
  const live = detail.offers.filter((o) => o.availability !== "OutOfStock" && o.price?.price != null);
  if (!live.length) return null;
  return live.find((o) => o.domain === merchant) ?? live.sort((a, b) => a.price.price - b.price.price)[0];
}

/** Null when the store has nothing to sell us: no in-stock offer, or no buyable size in the chosen colour. */
function toOffer(c: Candidate, detail: C3Product): RankedOffer | null {
  const offer = pickOffer(detail, c.merchant);
  if (!offer) return null;

  const sizeOption = sizeOptionOf(detail.variants);
  const sizes = sizeOption
    ? buyableSizes(sizeOption.values).map((v) => ({ label: v.label, size: normalizeSize(v.label), available: v.available }))
    : null;
  if (sizes && !sizes.length) return null; // e.g. the matching colour is sold out in every size

  const colorOption = colorOptionOf(detail.variants);
  const color = colorOption ? (detail.variants?.selected.find((s) => s.name === colorOption.name)?.label ?? null) : null;
  const colors: ColorOption[] = (colorOption?.values ?? [])
    .filter((v) => v.exists)
    .map((v) => ({
      label: v.label,
      hex: null,
      productId: v.product_id ?? null,
      available: v.available,
      imageUrl: v.thumbnail_url ?? null,
      selected: v.label === color,
    }));
  // Colourways Channel3 lists as separate products at the same store (same title), shown as photo swatches.
  if (c.colorways.length && !colors.length) {
    for (const p of [detail, ...c.colorways]) {
      colors.push({
        label: null,
        hex: p.colors?.[0]?.hex ?? null,
        productId: p.id,
        available: null,
        imageUrl: mainImage(p),
        selected: p.id === detail.id,
      });
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
    color,
    sizes,
    colors,
    matchedBy: [...c.sources],
    category: detail.category?.slug ?? null,
  };
}
