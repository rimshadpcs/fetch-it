// "Goes well with" ideas from the model, cleaned (pure). Run: npm test
import assert from "node:assert/strict";
import { test } from "node:test";
import { cleanIdeas } from "../lib/fetch/completeLook.js";

test("keeps 3 at most, trims, drops repeats and the bought kind of item", () => {
  const ideas = cleanIdeas(
    {
      ideas: [
        { label: "White Sneakers", query: "men's white leather sneakers", why: "Clean and casual with a knit polo" },
        { label: "polo", query: "men's knit polo", why: "another one" },
        { label: "white sneaker", query: "sneakers", why: "dup" },
        { label: "chinos", query: "men's beige slim chinos", why: "" },
        { label: "", query: "x", why: "" },
        { label: "sunglasses", query: "men's tortoiseshell sunglasses", why: "summer" },
        { label: "belt", query: "brown leather belt", why: "" },
      ],
    },
    "Striped Knit Polo Shirt",
  );
  assert.deepEqual(ideas.map((i) => i.label), ["white sneakers", "chinos", "sunglasses"]);
  assert.equal(cleanIdeas(null, "x").length, 0);
});

test("offers that cost far more than the item are dropped (cheapest kept if all are)", async () => {
  const { affordable } = await import("../lib/fetch/completeLook.js");
  assert.deepEqual(affordable([{ price: 69 }, { price: 799 }], 42.99).map((o) => o.price), [69]);
  assert.deepEqual(affordable([{ price: 900 }, { price: 450 }], 30).map((o) => o.price), [450]);
});
