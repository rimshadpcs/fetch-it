// Price history → sparkline points + "buy now or wait" (pure). Run: npm test
import assert from "node:assert/strict";
import { test } from "node:test";
import { dropToNotify, summarizeHistory } from "../lib/lib/pricehistory.js";

const today = new Date("2026-11-07T12:00:00Z");
const day = (d) => new Date(today.getTime() - d * 86_400_000).toISOString().slice(0, 10) + "T00:00:00Z";
const series = (prices) => prices.map((price, i) => ({ timestamp: day(prices.length - 1 - i), price, currency: "USD" }));

test("flat for the whole window → steady", () => {
  const s = summarizeHistory(series(Array(30).fill(33.63)), 33.63, today);
  assert.equal(s.verdict, "steady");
  assert.equal(s.points.length, 30);
  assert.match(s.message, /Same price for 30 days/);
});

test("now at the 30-day low → lowest", () => {
  const s = summarizeHistory(series([42.99, 42.99, 39.99, 42.99, 35]), 35, today);
  assert.equal(s.verdict, "lowest");
  assert.equal(s.low, 35);
});

test("cheaper earlier in the window → wait, naming the low and its day", () => {
  const s = summarizeHistory(series([42.99, 29.99, 29.99, 42.99, 42.99]), 42.99, today);
  assert.equal(s.verdict, "wait");
  assert.equal(s.low, 29.99);
  assert.equal(s.lowOn, day(2).slice(0, 10));
  assert.match(s.message, /It was \$29\.99 on Nov 5/);
});

test("only the last 30 days count, one point per day", () => {
  const old = { timestamp: day(45), price: 10, currency: "USD" };
  const twice = [{ timestamp: day(1).replace("T00", "T01"), price: 50, currency: "USD" }, { timestamp: day(1).replace("T00", "T09"), price: 40, currency: "USD" }];
  const s = summarizeHistory([old, ...twice, { timestamp: day(0), price: 40, currency: "USD" }], null, today);
  assert.deepEqual(s.points, [40, 40]);
  assert.equal(s.verdict, "steady");
});

test("the live price counts even when history hasn't caught up", () => {
  const s = summarizeHistory(series([42.99, 42.99, 42.99]), 38, today);
  assert.equal(s.verdict, "lowest");
  assert.equal(s.low, 38);
});

test("too little history → null", () => {
  assert.equal(summarizeHistory(series([42.99]), 42.99, today), null);
  assert.equal(summarizeHistory([], null, today), null);
});

test("drop pushes: only a new low since the watch started, never a bounce back", () => {
  const w = { startPrice: 49, lastPrice: 49, notifiedPrice: null };
  assert.equal(dropToNotify(w, 49), null, "no change");
  assert.equal(dropToNotify(w, 52), null, "went up");
  assert.equal(dropToNotify(w, 45), 49, "first drop, down from the start price");
  const after = { ...w, notifiedPrice: 45 };
  assert.equal(dropToNotify(after, 48), null);
  assert.equal(dropToNotify(after, 45), null, "back to a low we already pushed");
  assert.equal(dropToNotify(after, 43.5), 45, "a new low");
  assert.equal(dropToNotify({ startPrice: null, lastPrice: null }, 40), null, "nothing to compare with");
});
