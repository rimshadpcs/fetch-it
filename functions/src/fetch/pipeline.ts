import { Channel3, mainImage, type C3Filters, type C3Offer, type C3Product } from "../lib/channel3.js";
import type { AttemptTrace, DetectedItem } from "../lib/gemini.js";
import { identify } from "../lib/identify.js";
import { cropForSearch, prepareImage } from "../lib/image.js";
import { anchorPrice, filterByCategory, mergeAndDedupe, priceFactor, targetCategories, type Candidate, type PriceAnchor } from "./rank.js";
import { buyableSizes, chooseColor, colorOptionOf, normalizeSize, sizeOptionOf } from "./variants.js";

const DETAIL_COUNT = 5;
/** Offers hydrated with live prices before the price-sanity re-rank; the best DETAIL_COUNT are returned. */
const RANK_POOL = 7;
/**
 * Which price the price-sanity factor compares against (see anchorPrice). Oct 25 comparison on the same pools:
 * image_median ($885 lamp / $75 polo) and all_median ($675 / $75) let pricey vintage/designer look-alikes set the
 * bar — the $99 lamp sank or vanished and the polo's best visual match ($22.90, relevance 1.0) fell to #3.
 * cheaper_half ($99 / $33) kept the best match first in both and lifted the affordable look-alike to #2.
 */
export const PRICE_ANCHOR: PriceAnchor = "cheaper_half";
/** Items below this prominence are background clutter, dropped when there's a real subject. */
const CLUTTER = 0.3;
/** Continue without "Which one?" when the top item is this prominent and the next one isn't. */
const CLEAR_SUBJECT = 0.75;
const RUNNER_UP_MAX = 0.45;
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
  /** Variant option name for `color` ("Color", "Colour"), used to re-fetch the same variant at purchase. */
  colorOption: string | null;
  /** Sizes that exist in `color` and aren't sold out; null when the product has no size option. */
  sizes: SizeOption[] | null;
  colors: ColorOption[];
  matchedBy: string[];
  category: string | null;
  /** Why it's ranked where it is: final = relevance × priceFactor. */
  score: OfferScore;
}
export interface OfferScore {
  final: number;
  relevance: number;
  imageRank: number | null;
  textRank: number | null;
  priceFactor: number;
  medianPrice: number | null;
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
interface Common {
  items: DetectedItem[];
  model: string | null;
  /** Every Gemini call this request made (model, role, time, outcome); null when items came from a token. */
  identifyTrace: AttemptTrace[] | null;
  timings: Timings;
}
export type FetchResult =
  | (Common & {
      status: "results";
      itemIndex: number;
      offers: RankedOffer[];
      debug?: { pool: RankedOffer[]; anchors: Record<string, number | null> };
    })
  | (Common & { status: "choose" | "none" });

/**
 * Most prominent first, background clutter dropped (unless it's all there is). Returns the index to search
 * automatically, or null when the photo really has several candidates ("Which one?").
 */
export function selectSubjects(items: DetectedItem[]): { items: DetectedItem[]; auto: number | null } {
  const sorted = [...items].sort((a, b) => (b.prominence ?? 0) - (a.prominence ?? 0));
  const real = sorted.filter((i) => (i.prominence ?? 1) >= CLUTTER);
  const kept = real.length ? real : sorted;
  if (kept.length <= 1) return { items: kept, auto: kept.length ? 0 : null };
  const [top, next] = kept;
  const clear = (top.prominence ?? 0) >= CLEAR_SUBJECT && (next.prominence ?? 0) <= RUNNER_UP_MAX;
  return { items: kept, auto: clear ? 0 : null };
}

interface Deps {
  geminiKey: string;
  /** Claude Haiku 5.5 races Gemini in identify; without it Gemini runs alone. */
  anthropicKey?: string;
  /** Tuning only: also return the pre-price pool and every anchor, so ranking variants can be compared offline. */
  debugRank?: boolean;
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
  { geminiKey, anthropicKey, channel3Key, userId, itemIndex, items: knownItems, emit = () => {}, debugRank = false }: Deps,
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

  const hydrator = new OfferHydrator(c3);

  const img = await timed("resize", () => prepareImage(image));

  let items = knownItems;
  let model: string | null = null;
  let identifyTrace: AttemptTrace[] | null = null;
  let auto: number | null = null;
  if (!items) {
    const r = await timed("identify", () => identify({ gemini: geminiKey, anthropic: anthropicKey }, img.jpeg));
    ({ items, auto } = selectSubjects(r.items));
    model = r.model;
    identifyTrace = r.trace;
    emit({ type: "items", items, model });
  }
  const done = (status: "choose" | "none"): FetchResult => {
    timings.total = Math.round(performance.now() - start);
    return { status, items: items!, model, identifyTrace, timings };
  };
  if (!items.length) return done("none");
  if (itemIndex === undefined && auto === null) return done("choose");
  const index = itemIndex ?? auto ?? 0;
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
    r.products.slice(0, DETAIL_COUNT).forEach((p) => hydrator.prefetch(p.id));
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

  const { offers: pool, dropped } = await timed("product_detail", () => hydrator.hydrate(candidates, item.colors, RANK_POOL));
  timings.offers_dropped = dropped;
  // Price sanity against what the visual matches cost (search prices as an anchor only; offers carry live prices).
  const anchorInputs = [filterByCategory(imageRes.products, target), filterByCategory(textRes.products, target)] as const;
  const median = anchorPrice(...anchorInputs, PRICE_ANCHOR);
  const offers = pool
    .map((o) => {
      const factor = priceFactor(o.price, median);
      return { ...o, score: { ...o.score, priceFactor: round(factor), medianPrice: median, final: round(o.score.relevance * factor) } };
    })
    .sort((a, b) => b.score.final - a.score.final)
    .slice(0, DETAIL_COUNT);
  emit({ type: "offers", offers });

  timings.total = Math.round(performance.now() - start);
  const debug = debugRank
    ? {
        pool,
        anchors: Object.fromEntries((["image_median", "all_median", "cheaper_half"] as const).map((k) => [k, anchorPrice(...anchorInputs, k)])),
      }
    : undefined;
  return { status: "results", items, itemIndex: index, model, identifyTrace, offers, timings, ...(debug && { debug }) };
}

/**
 * Turns ranked candidates into offers with live detail prices: detail in the colour that matches, drop
 * listings with nothing buyable in that colour, one backfill wave. Shared by fetchItem and findSimilar.
 */
export class OfferHydrator {
  // Product detail is free but Channel3 serves parallel calls slowly (5 at once ≈ 1.5 s vs 0.35 s for one),
  // so callers can start likely winners early; promises are cached and reused.
  private cache = new Map<string, Promise<C3Product | null>>();
  constructor(private c3: Channel3) {}

  private detail(id: string, options: Record<string, string> = {}) {
    const key = `${id}|${JSON.stringify(options)}`;
    if (!this.cache.has(key)) this.cache.set(key, this.c3.product(id, options).catch(() => null));
    return this.cache.get(key)!;
  }

  prefetch(id: string) {
    void this.detail(id);
  }

  /**
   * Detail in the colour that matches the photo. Search results already list the colour options, so we can
   * usually ask for the right colour in one call; a prefetched default-colour detail is reused when it matches.
   */
  private async detailInColor(p: C3Product, wanted: string[]) {
    const option = colorOptionOf(p.variants);
    const color = chooseColor(p.variants, wanted);
    const plainKey = `${p.id}|{}`;
    if (!option || !color) return this.detail(p.id);
    if (this.cache.has(plainKey)) {
      const plain = await this.cache.get(plainKey)!;
      const selected = plain?.variants?.selected.find((s) => s.name === option.name)?.label;
      if (plain && selected === color) return plain;
    }
    return this.detail(p.id, { [option.name]: color });
  }

  async hydrate(candidates: Candidate[], wantedColors: string[], count = DETAIL_COUNT) {
    const offers: RankedOffer[] = [];
    let dropped = 0;
    let next = 0;
    for (let wave = 0; wave < MAX_WAVES && offers.length < count && next < candidates.length; wave++) {
      const batch = candidates.slice(next, next + count - offers.length);
      next += batch.length;
      const details = await Promise.all(batch.map((c) => this.detailInColor(c.product, wantedColors)));
      batch.forEach((c, i) => {
        const offer = details[i] && toOffer(c, details[i]);
        if (offer) offers.push(offer);
        else dropped++;
      });
    }
    return { offers, dropped };
  }
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
    colorOption: color && colorOption ? colorOption.name : null,
    sizes,
    colors,
    matchedBy: [...c.sources],
    score: {
      final: round(c.score),
      relevance: round(c.score),
      imageRank: c.ranks.image ?? c.ranks.similar ?? null,
      textRank: c.ranks.text ?? null,
      priceFactor: 1,
      medianPrice: null,
    },
    category: detail.category?.slug ?? null,
  };
}

const round = (n: number) => Math.round(n * 1000) / 1000;
