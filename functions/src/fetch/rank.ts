import type { C3Product } from "../lib/channel3.js";
import type { IdentifiedItem } from "../lib/gemini.js";

export type Source = "image" | "text";

/** One listing after dedupe: the best-ranked product plus its other colourways at the same store. */
export interface Candidate {
  product: C3Product;
  merchant: string;
  score: number;
  sources: Set<Source>;
  colorways: C3Product[];
}

const STOP = new Set(["and", "the", "with", "for", "vintage", "style", "home", "apparel", "accessories"]);
const words = (s: string) =>
  s
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter((w) => w.length >= 3 && !STOP.has(w))
    .map((w) => w.replace(/(es|s)$/, ""));

/**
 * Gemini gives free-text categories; Channel3 uses slugs ("table-lamps", "sweatshirts-3").
 * Target slugs = categories whose name shares a word with what Gemini saw, plus the categories
 * that dominate the text search (which Gemini's query drove, so it stays on-topic).
 */
export function targetCategories(item: IdentifiedItem, text: C3Product[], image: C3Product[]): Set<string> {
  const itemWords = new Set(words(`${item.item_type} ${item.category}`));
  const target = new Set<string>();
  for (const p of [...text, ...image]) {
    const c = p.category;
    if (c && words(`${c.title} ${c.slug}`).some((w) => itemWords.has(w))) target.add(c.slug);
  }
  const counts = new Map<string, number>();
  for (const p of text.slice(0, 10)) if (p.category) counts.set(p.category.slug, (counts.get(p.category.slug) ?? 0) + 1);
  for (const [slug, n] of counts) if (n >= 2) target.add(slug);
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
 * Reciprocal-rank fusion over both result lists (items found by both rise), deduped by product id
 * and by normalized title + merchant. Same title at the same store = colourways of one listing.
 */
export function mergeAndDedupe(lists: [Source, C3Product[]][]): Candidate[] {
  const byId = new Map<string, Candidate>();
  const byTitle = new Map<string, Candidate>();
  for (const [source, products] of lists) {
    products.forEach((p, rank) => {
      const titleKey = `${normalizeTitle(p.title)}|${merchantOf(p)}`;
      let c = byId.get(p.id) ?? byTitle.get(titleKey);
      if (!c) {
        c = { product: p, merchant: merchantOf(p), score: 0, sources: new Set(), colorways: [] };
        byTitle.set(titleKey, c);
      } else if (c.product.id !== p.id && !c.colorways.some((x) => x.id === p.id)) {
        c.colorways.push(p);
      }
      byId.set(p.id, c);
      c.score += 1 / (60 + rank);
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
