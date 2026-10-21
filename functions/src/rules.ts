// House rules engine. Pure and deterministic: no I/O, no clock, no LLM. The agent proposes; this disposes.

export interface HouseRules {
  currency: string;
  monthlyBudget: number;
  /** Auto-buy only at or under this price; above it the user approves with a fingerprint. */
  perItemLimit: number;
  /** Channel3 category slugs; an offer passes if any slug in its category path is listed. */
  allowedCategories: string[];
  askBeforeNewStore: boolean;
  onlyShipToSavedPlaces: boolean;
  askIfUnsureAboutSize: boolean;
}

export interface OfferForRules {
  /** Live price from Channel3 product detail, fetched right before deciding. Never the search price.
   *  Null when the store no longer lists it. */
  price: number | null;
  currency: string;
  /** Category path slugs, root first, e.g. ["home-garden", "lighting", "lamps", "table-lamps"]. */
  categoryPath: string[];
  merchant: string;
  inStock: boolean;
  /** Sizes for the chosen colour; null when the product has no sizes. */
  sizes: { label: string; size: string | null; available: string | null }[] | null;
}

export interface RulesInput {
  rules: HouseRules;
  /** Spent or reserved this month, in rules.currency. */
  monthToDate: number;
  offer: OfferForRules;
  /** Requested size label (as the store writes it, or canonical). */
  size?: string | null;
  /** The saved place the user picked, or null if it isn't one of their saved places. */
  place: { id: string; label: string } | null;
  /** Stores the user has bought from before. */
  knownStores: string[];
  /** A vaulted PayPal account to charge. */
  paymentLinked: boolean;
  /** Approval already given for this purchase: "ask" reasons stop blocking the auto-buy. */
  approved?: boolean;
}

export type Decision = "AUTO_BUY" | "NEEDS_APPROVAL" | "BLOCKED";

export interface Reason {
  rule: string;
  pass: boolean;
  /** What a failure does. */
  onFail: "ask" | "block";
  message: string;
}

const UNBUYABLE = new Set(["OutOfStock", "SoldOut", "Discontinued"]);

export function money(amount: number, currency = "USD"): string {
  const symbol = currency === "USD" ? "$" : `${currency} `;
  const rounded = Math.round(amount * 100) / 100;
  return `${symbol}${Number.isInteger(rounded) ? rounded : rounded.toFixed(2)}`;
}

const sameSize = (a: string, b: { label: string; size: string | null }) =>
  a.trim().toLowerCase() === b.label.trim().toLowerCase() || (b.size !== null && a.trim().toUpperCase() === b.size);

export function decide(input: RulesInput): { decision: Decision; reasons: Reason[] } {
  const { rules, offer, monthToDate, place, knownStores } = input;
  const m = (n: number) => money(n, rules.currency);
  const reasons: Reason[] = [];
  const add = (rule: string, pass: boolean, onFail: Reason["onFail"], message: string) =>
    reasons.push({ rule, pass, onFail, message });

  // Can it be bought at all?
  add("paypal", input.paymentLinked, "block", input.paymentLinked ? "PayPal is linked" : "Link PayPal first");
  if (offer.price === null) {
    add("in_stock", false, "block", `Not available at ${offer.merchant} any more`);
  } else {
    add("in_stock", offer.inStock, "block", offer.inStock ? `In stock at ${offer.merchant}` : `Out of stock at ${offer.merchant}`);
  }
  const currencyOk = offer.currency === rules.currency;
  add(
    "currency",
    currencyOk,
    "block",
    currencyOk ? `Priced in ${rules.currency}` : `Priced in ${offer.currency}, but your rules are in ${rules.currency}`,
  );

  // Category
  const allowed = offer.categoryPath.some((c) => rules.allowedCategories.includes(c));
  const leaf = offer.categoryPath.at(-1) ?? "uncategorized";
  add(
    "category",
    allowed,
    "block",
    allowed ? `${pretty(leaf)} is in your allowed categories` : `${pretty(offer.categoryPath[0] ?? leaf)} isn't in your allowed categories`,
  );

  // Money (skipped when there's no live price: in_stock already blocks)
  if (offer.price !== null) addMoney(offer.price);
  function addMoney(price: number) {
  const after = monthToDate + price;
  const withinBudget = after <= rules.monthlyBudget + 1e-9;
  add(
    "monthly_budget",
    withinBudget,
    "block",
    withinBudget
      ? `${m(after)} of your ${m(rules.monthlyBudget)} monthly budget after this`
      : `${m(after - rules.monthlyBudget)} over your ${m(rules.monthlyBudget)} monthly budget (${m(monthToDate)} spent so far)`,
  );
  const withinLimit = price <= rules.perItemLimit + 1e-9;
  add(
    "per_item_limit",
    withinLimit,
    "ask",
    withinLimit
      ? `${m(price)} is within your ${m(rules.perItemLimit)} limit`
      : `${m(price - rules.perItemLimit)} over your ${m(rules.perItemLimit)} limit`,
  );
  }

  // Store
  const known = knownStores.includes(offer.merchant);
  if (rules.askBeforeNewStore) {
    add("new_store", known, "ask", known ? `You've bought from ${offer.merchant} before` : `First time buying from ${offer.merchant}`);
  }

  // Shipping
  if (rules.onlyShipToSavedPlaces) {
    add("saved_place", place !== null, "block", place ? `Ships to ${place.label}` : "That address isn't one of your saved places");
  }

  // Size
  if (offer.sizes) {
    const wanted = input.size?.trim();
    const match = wanted ? offer.sizes.find((s) => sameSize(wanted, s)) : undefined;
    if (wanted && !match) {
      add("size", false, "block", `Size ${wanted} isn't available in this colour`);
    } else if (match && match.available && UNBUYABLE.has(match.available)) {
      add("size", false, "block", `Size ${match.label} is sold out`);
    } else if (match) {
      add("size", true, "ask", `Size ${match.label} is ${match.available === "InStock" ? "in stock" : "available"}`);
    } else if (rules.askIfUnsureAboutSize) {
      add("size", false, "ask", "I'm not sure which size you want");
    } else {
      add("size", false, "block", "Pick a size first");
    }
  }

  const failed = reasons.filter((r) => !r.pass);
  const decision: Decision = failed.some((r) => r.onFail === "block")
    ? "BLOCKED"
    : failed.length && !input.approved
      ? "NEEDS_APPROVAL"
      : "AUTO_BUY";
  return { decision, reasons };
}

const pretty = (slug: string) =>
  slug
    .replace(/-\d+$/, "")
    .split("-")
    .map((w, i) => (i === 0 ? w[0]?.toUpperCase() + w.slice(1) : w))
    .join(" ");
