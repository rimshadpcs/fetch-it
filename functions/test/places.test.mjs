// US address validation for addPlace (pure). Run: npm test
import assert from "node:assert/strict";
import { test } from "node:test";
import { validatePlace } from "../lib/lib/address.js";

const ok = { label: "Home", fullName: "Sam Rivera", line1: "2211 N First St", line2: "", city: "San Jose", state: "ca", postalCode: "95131" };

test("a normal US address is accepted and normalised", () => {
  const r = validatePlace({ ...ok, line1: "  2211  N First St ", phone: "(408) 555-0123" });
  assert.ok("place" in r, JSON.stringify(r));
  assert.equal(r.place.state, "CA");
  assert.equal(r.place.line1, "2211 N First St");
  assert.equal(r.place.line2, null);
  assert.equal(r.place.countryCode, "US");
  assert.equal(r.place.phone, "+14085550123");
});

test("ZIP+4, PO boxes, DC and Puerto Rico are fine", () => {
  for (const p of [{ postalCode: "95131-1002" }, { line1: "PO Box 12" }, { state: "DC", city: "Washington", postalCode: "20001" }, { state: "PR", city: "San Juan", postalCode: "00901" }]) {
    assert.ok("place" in validatePlace({ ...ok, ...p }), JSON.stringify(p));
  }
});

test("every bad field is reported by name", () => {
  const r = validatePlace({ label: "", fullName: "S", line1: "Main St", city: "S4n", state: "XX", postalCode: "9513", phone: "12345" });
  assert.ok("errors" in r);
  assert.deepEqual(Object.keys(r.errors).sort(), ["city", "fullName", "label", "line1", "phone", "postalCode", "state"]);
});

test("phone is optional; +1 and punctuation are stripped; 555-style area codes starting 0/1 are rejected", () => {
  assert.equal(validatePlace({ ...ok }).place.phone, null);
  assert.equal(validatePlace({ ...ok, phone: "+1 650.555.0199" }).place.phone, "+16505550199");
  assert.ok("errors" in validatePlace({ ...ok, phone: "150-555-0199" }));
});

test("custom labels up to 24 characters, no symbols", () => {
  assert.ok("place" in validatePlace({ ...ok, label: "Mum's flat" }));
  assert.ok("errors" in validatePlace({ ...ok, label: "x".repeat(25) }));
  assert.ok("errors" in validatePlace({ ...ok, label: "<b>home</b>" }));
});
