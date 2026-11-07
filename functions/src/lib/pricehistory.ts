// "Buy now or wait": a watched product's last 30 days of Channel3 price history, boiled down to what the app shows
// (a sparkline + Scout's call). Pure, so it's unit-tested. History is per Channel3 product (not pinned to one store),
// so the copy talks about "the price", never "this store's price".

export interface HistoryPoint {
  timestamp: string;
  price: number;
  currency: string;
}

export type Verdict = "lowest" | "wait" | "steady";

export interface HistorySummary {
  /** One price per day, oldest first (≤ 30), for the sparkline. */
  points: number[];
  low: number;
  high: number;
  /** YYYY-MM-DD of the most recent day at the low price. */
  lowOn: string;
  days: number;
  verdict: Verdict;
  message: string;
  currency: string;
}

const money = (n: number, currency = "USD") =>
  new Intl.NumberFormat("en-US", { style: "currency", currency, minimumFractionDigits: n % 1 === 0 ? 0 : 2 }).format(n);

const shortDate = (ymd: string) =>
  new Date(`${ymd}T12:00:00Z`).toLocaleDateString("en-US", { month: "short", day: "numeric", timeZone: "UTC" });

/**
 * [now]: the live price when we have it (watches keep it as lastPrice), else the last history point.
 * Returns null when there's no usable history.
 */
export function summarizeHistory(history: HistoryPoint[], now: number | null, today = new Date(), days = 30): HistorySummary | null {
  const cutoff = new Date(today.getTime() - days * 86_400_000).toISOString().slice(0, 10);
  // One point per day (the last one that day), within the window.
  const byDay = new Map<string, HistoryPoint>();
  for (const p of history) {
    if (typeof p.price !== "number" || !(p.price > 0) || !p.timestamp) continue;
    const day = p.timestamp.slice(0, 10);
    if (day > cutoff) byDay.set(day, p);
  }
  const daily = [...byDay.entries()].sort(([a], [b]) => a.localeCompare(b));
  if (daily.length < 2) return null;
  const points = daily.map(([, p]) => p.price);
  const currency = daily[daily.length - 1][1].currency || "USD";
  const current = now ?? points[points.length - 1];
  const low = Math.min(...points, current);
  const high = Math.max(...points, current);
  const lowOn = [...daily].reverse().find(([, p]) => p.price <= low + 0.005)?.[0] ?? daily[daily.length - 1][0];
  const span = daily.length;

  let verdict: Verdict;
  let message: string;
  if (high - low < 0.01) {
    verdict = "steady";
    message = `Same price for ${span} days. No sale in sight: buy whenever you're ready.`;
  } else if (current <= low + 0.005) {
    verdict = "lowest";
    message = `Lowest price in ${span} days. Good time to buy.`;
  } else {
    verdict = "wait";
    message = `It was ${money(low, currency)} on ${shortDate(lowOn)}. I'd wait for a drop.`;
  }
  return { points, low, high, lowOn, days: span, verdict, message, currency };
}

/**
 * A drop that hasn't reached the target yet: the watch check pushes "Price dropped" (nothing is bought). Returns the price to say it's down from, or null.
 * Only a new low since the watch started counts, so $49 → $45 → $48 → $45 notifies once; the next push needs a price under $45.
 */
export function dropToNotify(w: { startPrice: number | null; lastPrice: number | null; notifiedPrice?: number | null }, price: number): number | null {
  const bar = w.notifiedPrice ?? w.startPrice ?? w.lastPrice;
  if (bar == null || !(price < bar - 0.005)) return null;
  return bar;
}
