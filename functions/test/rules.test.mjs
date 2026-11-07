// Unit tests for the house rules engine. Run: npm test
import { test } from "node:test";
import assert from "node:assert/strict";
import { decide, money, profileSize } from "../lib/rules.js";

const rules = {
  currency: "USD",
  monthlyBudget: 300,
  perItemLimit: 75,
  allowedCategories: ["home-garden", "apparel-accessories"],
  askBeforeNewStore: true,
  onlyShipToSavedPlaces: true,
  askIfUnsureAboutSize: true,
};
const lamp = {
  price: 42,
  currency: "USD",
  categoryPath: ["home-garden", "lighting", "lamps", "table-lamps"],
  merchant: "lamps.example",
  inStock: true,
  sizes: null,
};
const home = { id: "home", label: "Home" };
const base = { rules, monthToDate: 0, offer: lamp, place: home, knownStores: ["lamps.example"], paymentLinked: true };
const failed = (r) => r.reasons.filter((x) => !x.pass).map((x) => x.rule);
const msg = (r, rule) => r.reasons.find((x) => x.rule === rule).message;

test("within every rule → AUTO_BUY, all reasons pass", () => {
  const r = decide(base);
  assert.equal(r.decision, "AUTO_BUY");
  assert.deepEqual(failed(r), []);
  assert.equal(msg(r, "per_item_limit"), "$42 is within your $75 limit");
});

test("over the per-item limit → NEEDS_APPROVAL with the overage in words", () => {
  const r = decide({ ...base, offer: { ...lamp, price: 128 } });
  assert.equal(r.decision, "NEEDS_APPROVAL");
  assert.deepEqual(failed(r), ["per_item_limit"]);
  assert.equal(msg(r, "per_item_limit"), "$53 over your $75 limit");
});

test("approval turns ask-failures into AUTO_BUY but never overrides a block", () => {
  assert.equal(decide({ ...base, offer: { ...lamp, price: 128 }, approved: true }).decision, "AUTO_BUY");
  const blocked = decide({ ...base, monthToDate: 290, approved: true });
  assert.equal(blocked.decision, "BLOCKED");
});

test("category outside the allowed list → NEEDS_APPROVAL; a fingerprint can override it", () => {
  const r = decide({ ...base, offer: { ...lamp, categoryPath: ["electronics", "audio", "headphones"] } });
  assert.equal(r.decision, "NEEDS_APPROVAL");
  assert.deepEqual(failed(r), ["category"]);
  assert.equal(msg(r, "category"), "Electronics isn't in your allowed categories");
  assert.equal(decide({ ...base, offer: { ...lamp, categoryPath: ["electronics", "audio"] }, approved: true }).decision, "AUTO_BUY");
});

test("category names use Channel3's own titles", () => {
  const perfume = { ...lamp, categoryPath: ["health-beauty", "personal-care", "cosmetics", "perfume-cologne"] };
  assert.equal(msg(decide({ ...base, offer: perfume }), "category"), "Health & Beauty isn't in your allowed categories");
  assert.equal(decide({ ...base, offer: perfume, rules: { ...rules, allowedCategories: ["health-beauty"] } }).decision, "AUTO_BUY");
});

test("monthly budget is a hard cap, counting what's already spent", () => {
  const r = decide({ ...base, monthToDate: 280 });
  assert.equal(r.decision, "BLOCKED");
  assert.equal(msg(r, "monthly_budget"), "$22 over your $300 monthly budget ($280 spent so far)");
  assert.equal(decide({ ...base, monthToDate: 258 }).decision, "AUTO_BUY"); // exactly at budget is fine
});

test("new store → NEEDS_APPROVAL only when the rule is on", () => {
  const r = decide({ ...base, knownStores: [] });
  assert.equal(r.decision, "NEEDS_APPROVAL");
  assert.equal(msg(r, "new_store"), "First time buying from lamps.example");
  assert.equal(decide({ ...base, knownStores: [], rules: { ...rules, askBeforeNewStore: false } }).decision, "AUTO_BUY");
});

test("unsaved address → BLOCKED when only-ship-to-saved-places is on", () => {
  assert.equal(decide({ ...base, place: null }).decision, "BLOCKED");
  assert.equal(decide({ ...base, place: null, rules: { ...rules, onlyShipToSavedPlaces: false } }).decision, "AUTO_BUY");
});

test("out of stock or wrong currency → BLOCKED", () => {
  assert.equal(decide({ ...base, offer: { ...lamp, inStock: false } }).decision, "BLOCKED");
  assert.equal(decide({ ...base, offer: { ...lamp, currency: "EUR" } }).decision, "BLOCKED");
});

const hoodie = {
  ...lamp,
  price: 40,
  categoryPath: ["apparel-accessories", "clothing", "hoodies-3"],
  sizes: [
    { label: "SML", size: "S", available: "InStock" },
    { label: "MED", size: "M", available: "OutOfStock" },
    { label: "LRG", size: "L", available: "InStock" },
  ],
};

test("sizes: canonical or store label both match; sold out and missing sizes block", () => {
  assert.equal(decide({ ...base, offer: hoodie, size: "L" }).decision, "AUTO_BUY");
  assert.equal(decide({ ...base, offer: hoodie, size: "lrg" }).decision, "AUTO_BUY");
  const soldOut = decide({ ...base, offer: hoodie, size: "M" });
  assert.equal(soldOut.decision, "BLOCKED");
  assert.equal(msg(soldOut, "size"), "Size MED is sold out");
  assert.equal(decide({ ...base, offer: hoodie, size: "XL" }).decision, "BLOCKED");
});

test("no size given → ask (or block when ask-if-unsure is off)", () => {
  const r = decide({ ...base, offer: hoodie });
  assert.equal(r.decision, "NEEDS_APPROVAL");
  assert.equal(msg(r, "size"), "I'm not sure which size you want");
  assert.equal(decide({ ...base, offer: hoodie, rules: { ...rules, askIfUnsureAboutSize: false } }).decision, "BLOCKED");
});

test("several failures are all reported; block wins over ask", () => {
  const r = decide({ ...base, monthToDate: 250, offer: { ...lamp, price: 128, categoryPath: ["electronics"] }, knownStores: [] });
  assert.equal(r.decision, "BLOCKED");
  assert.deepEqual(failed(r).sort(), ["category", "monthly_budget", "new_store", "per_item_limit"]);
});

test("store no longer lists it → BLOCKED with no money reasons", () => {
  const r = decide({ ...base, offer: { ...lamp, price: null, inStock: false } });
  assert.equal(r.decision, "BLOCKED");
  assert.equal(msg(r, "in_stock"), "Not available at lamps.example any more");
  assert.equal(r.reasons.some((x) => x.rule === "per_item_limit" || x.rule === "monthly_budget"), false);
});

test("no linked PayPal → BLOCKED", () => {
  const r = decide({ ...base, paymentLinked: false });
  assert.equal(r.decision, "BLOCKED");
  assert.equal(msg(r, "paypal"), "Link PayPal first");
});

test("money formatting", () => {
  assert.equal(money(53), "$53");
  assert.equal(money(42.5), "$42.50");
  assert.equal(money(10, "EUR"), "EUR 10");
});

// ---------- sizes from the saved profile (setProfile) ----------
const sweatshirt = {
  price: 40,
  currency: "USD",
  categoryPath: ["apparel-accessories", "clothing", "shirts-tops", "sweatshirts-3"],
  merchant: "lamps.example",
  inStock: true,
  sizes: [
    { label: "SML", size: "S", available: "InStock" },
    { label: "MED", size: "M", available: "InStock" },
    { label: "LRG", size: "L", available: "OutOfStock" },
  ],
};
const profile = { sizes: { top: "M", waist: "32", shoe: "US 9", fit: "Regular" }, askIfUnsure: true };

test("profileSize picks the slot from the category path", () => {
  assert.equal(profileSize(sweatshirt.categoryPath, profile), "M");
  assert.equal(profileSize(["apparel-accessories", "clothing", "pants", "joggers-4"], profile), "32");
  assert.equal(profileSize(["apparel-accessories", "shoes", "sneakers"], profile), "9");
  assert.equal(profileSize(["apparel-accessories", "shoes"], { sizes: { shoe: "EU 42" } }), null, "no US/EU guessing");
  assert.equal(profileSize(sweatshirt.categoryPath, null), null);
});

test("no size asked: the saved size in stock → AUTO_BUY with the store's label", () => {
  const r = decide({ ...base, offer: sweatshirt, profile });
  assert.equal(r.decision, "AUTO_BUY");
  assert.equal(r.size, "MED");
  assert.equal(msg(r, "size"), "Your size MED is in stock");
});

test("saved size sold out → BLOCKED", () => {
  const r = decide({ ...base, offer: sweatshirt, profile: { ...profile, sizes: { top: "L" } } });
  assert.equal(r.decision, "BLOCKED");
  assert.equal(msg(r, "size"), "Your size LRG is sold out");
});

test("an explicit size wins over the profile", () => {
  const r = decide({ ...base, offer: sweatshirt, size: "S", profile });
  assert.equal(r.size, "SML");
  assert.equal(msg(r, "size"), "Size SML is in stock");
});

test("no saved size: the profile's askIfUnsure decides between asking and blocking", () => {
  assert.equal(decide({ ...base, offer: sweatshirt, profile: { askIfUnsure: true } }).decision, "NEEDS_APPROVAL");
  const r = decide({ ...base, offer: sweatshirt, profile: { askIfUnsure: false } });
  assert.equal(r.decision, "BLOCKED");
  assert.equal(msg(r, "size"), "Pick a size first");
});

test("gift: checked against the gift budget (not the monthly one); no gift budget → blocked", () => {
  const mom = { ...base, place: { id: "mom", label: "Mom" }, monthToDate: 299, rules: { ...rules, giftBudget: 100 } };
  const ok = decide({ ...mom, gift: { to: "Mom", monthToDate: 20 } });
  assert.equal(ok.decision, "AUTO_BUY");
  assert.equal(msg(ok, "gift_budget"), "$62 of your $100 gift budget after this");
  assert.equal(ok.reasons.some((r) => r.rule === "monthly_budget"), false, "a gift doesn't touch the monthly budget");
  const over = decide({ ...mom, gift: { to: "Mom", monthToDate: 80 } });
  assert.equal(over.decision, "BLOCKED");
  assert.equal(msg(over, "gift_budget"), "$22 over your $100 gift budget ($80 on gifts so far)");
  const none = decide({ ...mom, rules, gift: { to: "Mom", monthToDate: 0 } });
  assert.equal(msg(none, "gift_budget"), "Set a gift budget in your house rules first");
  assert.equal(none.decision, "BLOCKED");
});

test("gift: never uses the user's own saved size; asks which size for them", () => {
  const shirt = { ...lamp, categoryPath: ["apparel-accessories", "clothing", "shirts-tops"], sizes: [{ label: "S", size: "S", available: "InStock" }, { label: "M", size: "M", available: "InStock" }] };
  const r = decide({ ...base, offer: shirt, rules: { ...rules, giftBudget: 100 }, profile: { sizes: { top: "M" } }, gift: { to: "Mom", monthToDate: 0 } });
  assert.equal(r.decision, "NEEDS_APPROVAL");
  assert.equal(msg(r, "size"), "Which size for Mom?");
  const picked = decide({ ...base, offer: shirt, size: "S", rules: { ...rules, giftBudget: 100 }, gift: { to: "Mom", monthToDate: 0 } });
  assert.equal(picked.decision, "AUTO_BUY");
});
