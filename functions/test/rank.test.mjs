// Unit tests for ranking helpers (price anchor + price-sanity factor). Run: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import { anchorPrice, priceFactor, relevance } from "../lib/fetch/rank.js";

const p = (id, price) => ({ id, title: id, offers: [{ domain: "x.example", price: { price, currency: "USD" } }] });
// Lamp-like: a few cheap modern lamps, many pricey vintage look-alikes in image search.
const image = [p("a", 367), p("b", 1604), p("c", 909), p("d", 1216), p("e", 5475), p("f", 89.99)];
const text = [p("g", 99), p("h", 42.99), p("a", 367), p("i", 25.64)];

test("image_median uses image matches only", () => {
  assert.equal(anchorPrice(image, text, "image_median"), (909 + 1216) / 2);
});

test("all_median dedupes products seen in both lists", () => {
  // 9 unique prices: 25.64, 42.99, 89.99, 99, 367, 909, 1216, 1604, 5475 → median 367
  assert.equal(anchorPrice(image, text, "all_median"), 367);
});

test("cheaper_half is the median of the cheaper half of all matches", () => {
  // cheaper 5 of 9: 25.64, 42.99, 89.99, 99, 367 → 89.99
  assert.equal(anchorPrice(image, text, "cheaper_half"), 89.99);
});

test("anchors ignore missing/zero prices and return null with no prices", () => {
  assert.equal(anchorPrice([p("z", 0), { id: "y", title: "y", offers: [] }], [], "cheaper_half"), null);
  assert.equal(anchorPrice([p("only", 50)], [], "cheaper_half"), 50);
});

test("priceFactor: 1 inside ½×–2× of the anchor, shrinks outside, floors at 0.35", () => {
  assert.equal(priceFactor(100, 100), 1);
  assert.equal(priceFactor(200, 100), 1);
  assert.equal(priceFactor(50, 100), 1);
  assert.equal(priceFactor(400, 100), 0.5);
  assert.equal(priceFactor(25, 100), 0.5);
  assert.equal(priceFactor(100000, 100), 0.35);
  assert.equal(priceFactor(100, null), 1);
});

test("relevance weighs image matches above text matches", () => {
  assert.ok(relevance({ image: 0 }) > relevance({ text: 0 }));
  assert.ok(relevance({ image: 0, text: 0 }) > relevance({ image: 0 }));
  assert.equal(relevance({ image: 4 }), 0.5);
});
