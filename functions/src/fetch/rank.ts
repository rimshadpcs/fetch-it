import type { C3Product } from "../lib/channel3.js";
import type { DetectedItem } from "../lib/gemini.js";

export type Source = "image" | "text" | "similar";

/** One listing after dedupe: the best-ranked product plus its other colourways at the same store. */
export interface Candidate {
  product: C3Product;
  merchant: string;
  /** Position-weighted relevance across sources (see relevance()). */
  score: number;
  sources: Set<Source>;
  /** Best (0-based) position this listing reached in each result list. */
  ranks: Partial<Record<Source, number>>;
  colorways: C3Product[];
}

/** Image similarity is the strongest signal of "this is the thing in the photo"; text matches are broader. */
export const SOURCE_WEIGHT: Record<Source, number> = { image: 1, similar: 1, text: 0.6 };
/** 1 for the top result, 0.5 at position 5, 0.31 at position 10. */
const positional = (rank: number) => 1 / (1 + rank * 0.25);
export const relevance = (ranks: Candidate["ranks"]) =>
  (Object.entries(ranks) as [Source, number][]).reduce((sum, [src, rank]) => sum + SOURCE_WEIGHT[src] * positional(rank), 0);

/** Median price of a result list (an anchor for "what this kind of thing costs", not a price we show). */
export function medianPrice(products: C3Product[]): number | null {
  const prices = products
    .map((p) => p.offers[0]?.price?.price)
    .filter((n): n is number => typeof n === "number" && n > 0)
    .sort((a, b) => a - b);
  if (!prices.length) return null;
  const mid = Math.floor(prices.length / 2);
  return prices.length % 2 ? prices[mid] : (prices[mid - 1] + prices[mid]) / 2;
}

/**
 * Price sanity: 1 inside ½×–2× the median of the visual matches; outside, shrinks with distance in
 * doublings (0.5 at 4× or ¼×) down to a floor of 0.35, so extreme outliers sink but never disappear.
 */
export function priceFactor(price: number, median: number | null): number {
  if (!median || !(price > 0)) return 1;
  const doublings = Math.abs(Math.log2(price / median));
  return doublings <= 1 ? 1 : Math.max(0.35, 1 / doublings);
}

const STOP = new Set(["and", "the", "with", "for", "vintage", "style", "home", "apparel", "accessories"]);
const words = (s: string) =>
  s
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter((w) => w.length >= 3 && !STOP.has(w))
    .map((w) => w.replace(/(es|s)$/, ""));

const parentOf = (c: C3Product["category"]) => c?.path.at(-2)?.slug ?? null;
function dominant(products: C3Product[]): Map<string, C3Product["category"]> {
  const counts = new Map<string, { n: number; cat: C3Product["category"] }>();
  for (const p of products.slice(0, 10)) {
    if (!p.category) continue;
    const e = counts.get(p.category.slug) ?? { n: 0, cat: p.category };
    e.n++;
    counts.set(p.category.slug, e);
  }
  return new Map([...counts].filter(([, e]) => e.n >= 2).map(([slug, e]) => [slug, e.cat]));
}

/**
 * Gemini gives free-text categories; Channel3 uses slugs ("table-lamps", "sweatshirts-3").
 * Target slugs =
 *  - categories whose name shares a word with what Gemini saw;
 *  - categories that dominate the text search (Gemini's query drove it, so it stays on-topic);
 *  - categories that dominate the image search *and* are siblings of those (same parent): Gemini may say
 *    "trousers" for joggers (pants > joggers-4 vs pants > trousers-3), while a fleece's look-alike jackets
 *    (outerwear > coats-jackets vs shirts-tops > sweatshirts-3) stay out.
 */
export function targetCategories(item: DetectedItem, text: C3Product[], image: C3Product[]): Set<string> {
  const itemWords = new Set(words(`${item.item_type} ${item.category}`));
  const target = new Set<string>();
  const parents = new Set<string>();
  const add = (c: NonNullable<C3Product["category"]>) => {
    target.add(c.slug);
    const parent = parentOf(c);
    if (parent) parents.add(parent);
  };
  for (const p of [...text, ...image]) {
    const c = p.category;
    if (c && words(`${c.title} ${c.slug}`).some((w) => itemWords.has(w))) add(c);
  }
  for (const cat of dominant(text).values()) if (cat) add(cat);
  for (const [slug, cat] of dominant(image)) if (parents.has(parentOf(cat) ?? "")) target.add(slug);
  return target;
}

export const normalizeTitle = (t: string) =>
  t
    .normalize("NFKC")
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim();

const merchantOf = (p: C3Product) => p.offers[0]?.domain ?? "unknown";

/**
 * Merge result lists into one ranked list: position-weighted relevance with image above text (items found
 * by both rise), deduped by product id and by normalized title + merchant (same title at one store =
 * colourways of one listing). Price sanity is applied later, once live prices are known.
 */
export function mergeAndDedupe(lists: [Source, C3Product[]][]): Candidate[] {
  const byId = new Map<string, Candidate>();
  const byTitle = new Map<string, Candidate>();
  for (const [source, products] of lists) {
    products.forEach((p, rank) => {
      const titleKey = `${normalizeTitle(p.title)}|${merchantOf(p)}`;
      let c = byId.get(p.id) ?? byTitle.get(titleKey);
      if (!c) {
        c = { product: p, merchant: merchantOf(p), score: 0, sources: new Set(), ranks: {}, colorways: [] };
        byTitle.set(titleKey, c);
      } else if (c.product.id !== p.id && !c.colorways.some((x) => x.id === p.id)) {
        c.colorways.push(p);
      }
      byId.set(p.id, c);
      c.ranks[source] = Math.min(c.ranks[source] ?? Infinity, rank);
      c.score = relevance(c.ranks);
      c.sources.add(source);
    });
  }
  return [...new Set(byId.values())].sort((a, b) => b.score - a.score);
}

export function filterByCategory(products: C3Product[], target: Set<string>): C3Product[] {
  if (target.size === 0) return products;
  const kept = products.filter((p) => p.category && target.has(p.category.slug));
  return kept.length ? kept : products; // never filter everything away
}
