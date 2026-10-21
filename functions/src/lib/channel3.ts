// Channel3 REST client (docs.trychannel3.com). Only the fields we use are typed.

const BASE = "https://api.trychannel3.com";

export interface C3Price {
  price: number;
  compare_at_price: number | null;
  currency: string;
}
export interface C3Offer {
  url: string;
  domain: string;
  price: C3Price;
  availability: string;
}
export interface C3OptionValue {
  label: string;
  exists: boolean;
  /** Always null on search; filled in by product detail. */
  available: string | null;
  thumbnail_url?: string | null;
  product_id?: string | null;
}
export interface C3Variants {
  options: { name: string; values: C3OptionValue[] }[];
  selected: { name: string; label: string }[];
}
export interface C3Category {
  slug: string;
  title: string;
  path: { slug: string; title: string }[];
}
export interface C3Product {
  id: string;
  title: string;
  brands?: { id: string; name: string }[];
  images?: { url: string; cleaned_url: string | null; is_main_image: boolean }[];
  category: C3Category | null;
  /** Dominant colours of the product photo, largest share first. */
  colors?: { hex: string; percentage: number }[];
  offers: C3Offer[];
  variants: C3Variants | null;
}
export interface C3SearchResponse {
  products: C3Product[];
  next_page_token: string | null;
}

export interface C3Filters {
  /** Age-agnostic products count as adult. */
  age?: string[];
  /** Strict: "male" returns only male-tagged products (unisex and untagged are dropped). */
  gender?: "male" | "female";
}

/** Passing any filters replaces Channel3's defaults (new + InStock), so we always restate them. */
const withDefaults = (f: C3Filters) => ({ availability: ["InStock", "LimitedAvailability"], conditions: ["new"], ...f });

export class Channel3 {
  constructor(
    private apiKey: string,
    private userId?: string,
  ) {}

  private async call<T>(path: string, body?: unknown, timeoutMs = 8000): Promise<T> {
    const res = await fetch(`${BASE}${path}`, {
      method: body ? "POST" : "GET",
      headers: {
        "x-api-key": this.apiKey,
        "Content-Type": "application/json",
        // Ties buy-link clicks and commissions to our user (Channel3 reporting).
        ...(this.userId && { "x-user-id": this.userId }),
      },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!res.ok) throw new Error(`Channel3 ${path} HTTP ${res.status}: ${(await res.text()).slice(0, 300)}`);
    return (await res.json()) as T;
  }

  // limit 10: ~1.1 s and steady; limit 20 swings 1.4–3.6 s. We only show the top 5.
  textSearch(query: string, filters: C3Filters = {}, limit = 10) {
    return this.call<C3SearchResponse>("/v1/search", { query, limit, filters: withDefaults(filters) });
  }

  imageSearch(jpeg: Buffer, filters: C3Filters = {}, limit = 10) {
    return this.call<C3SearchResponse>("/v1/image-search", {
      base64_image: jpeg.toString("base64"),
      limit,
      filters: withDefaults(filters),
    });
  }

  /**
   * Free. The only source of real prices and per-size stock. `options` selects a variant, e.g. {Color: "Coco"}
   * → `?option_Color=Coco`; sizes, price and images then describe that colour.
   * Detail returns ONE preferred store's offer unless `websiteIds` pins it (domains work): search can list
   * Best Buy at $34.99 while detail only shows the brand's own store at $49.99.
   */
  product(id: string, options: Record<string, string> = {}, websiteIds: string[] = []) {
    const qs = new URLSearchParams(Object.entries(options).map(([name, label]) => [`option_${name}`, label]));
    if (websiteIds.length) qs.set("website_ids", websiteIds.join(","));
    const query = qs.size ? `?${qs}` : "";
    return this.call<C3Product>(`/v1/products/${encodeURIComponent(id)}${query}`, undefined, 5000);
  }
}

export const mainImage = (p: C3Product) => {
  const img = p.images?.find((i) => i.is_main_image) ?? p.images?.[0];
  return img ? (img.cleaned_url ?? img.url) : null;
};
