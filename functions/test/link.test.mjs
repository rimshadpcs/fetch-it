// Share a link to Fetch It: link kinds, preview images, private-address guard (pure). Run: npm test
import assert from "node:assert/strict";
import { test } from "node:test";
import { biggerPin, classify, firstUrl, isPrivateAddress, previewImage } from "../lib/fetch/link.js";

test("finds the link in shared text and tells Pinterest / Instagram / pages apart", () => {
  assert.equal(firstUrl("Look at this! https://pin.it/3xYz9 (so cute).").href, "https://pin.it/3xYz9");
  assert.equal(firstUrl("no link here"), null);
  assert.deepEqual(classify(new URL("https://in.pinterest.com/pin/516717757264399280/")), { kind: "pinterest", pinId: "516717757264399280" });
  assert.deepEqual(classify(new URL("https://pin.it/3xYz9")), { kind: "pinterest", pinId: null });
  assert.deepEqual(classify(new URL("https://www.pinterest.co.uk/pin/123456789/")), { kind: "pinterest", pinId: "123456789" });
  assert.equal(classify(new URL("https://www.instagram.com/p/Cabc123/?igsh=x")).kind, "instagram");
  assert.equal(classify(new URL("https://www.wayfair.com/lighting/pdp/lamp.html")).kind, "page");
  assert.equal(classify(new URL("https://notpinterest.com/pin/123456")).kind, "page");
});

test("preview image: any attribute order, entities decoded, relative URLs resolved", () => {
  const page = new URL("https://shop.example.com/p/1");
  assert.equal(previewImage('<meta content="https://s.pinimg.com/a.png" data-app="true" name="og:image" property="og:image"/>', page).href, "https://s.pinimg.com/a.png");
  assert.equal(previewImage('<meta property="og:image" content="/img/lamp.jpg?w=800&amp;h=800">', page).href, "https://shop.example.com/img/lamp.jpg?w=800&h=800");
  assert.equal(previewImage("<meta name='twitter:image' content='https://cdn.example.com/t.jpg'>", page).href, "https://cdn.example.com/t.jpg");
  assert.equal(previewImage('<meta property="og:title" content="Lamp">', page), null);
  assert.equal(biggerPin("https://i.pinimg.com/236x/71/d3/10/71d3.jpg"), "https://i.pinimg.com/736x/71/d3/10/71d3.jpg");
});

test("never fetches private, loopback or metadata addresses", () => {
  for (const ip of ["127.0.0.1", "10.0.0.5", "169.254.169.254", "172.20.1.1", "192.168.1.1", "100.100.1.1", "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:10.0.0.1"]) {
    assert.ok(isPrivateAddress(ip), ip);
  }
  for (const ip of ["151.101.0.84", "142.250.72.14", "2a04:4e42::84"]) assert.ok(!isPrivateAddress(ip), ip);
});
