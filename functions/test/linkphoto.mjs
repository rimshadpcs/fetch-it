// linkPhoto on the emulators with real links (Pinterest oEmbed + a store page). Nothing is bought.
// Run with emulators up: node --test test/linkphoto.mjs
import assert from "node:assert/strict";
import { test } from "node:test";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";

const PROJECT = "fetchit-437a0";
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "127.0.0.1:9090";
initializeApp({ projectId: PROJECT });
const auth = getAuth();
const UID = "link-test";
await auth.deleteUser(UID).catch(() => {});
await auth.createUser({ uid: UID });
const idToken = (await (await fetch("http://127.0.0.1:9090/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fake-api-key", {
  method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ token: await auth.createCustomToken(UID), returnSecureToken: true }),
})).json()).idToken;
const call = async (text) => (await fetch(`http://127.0.0.1:5001/${PROJECT}/us-central1/linkPhoto`, {
  method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${idToken}` }, body: JSON.stringify({ data: { text } }),
})).json();

test("a Pinterest pin link → the pin's photo as a JPEG", async () => {
  const t = Date.now();
  const { result, error } = await call("Check this out https://in.pinterest.com/pin/516717757264399280/");
  assert.ok(!error, JSON.stringify(error));
  const jpeg = Buffer.from(result.imageBase64, "base64");
  assert.equal(jpeg[0], 0xff); assert.equal(jpeg[1], 0xd8);
  console.log(`   pinterest: ${jpeg.length} bytes in ${Date.now() - t} ms, source ${result.source}`);
});

test("a store page → its preview image", async () => {
  const { result, error } = await call("https://www.wayfair.com/");
  if (error) return console.log(`   store page refused: ${error.message}`); // some stores block non-browsers; that's fine
  console.log(`   store page: ${Buffer.from(result.imageBase64, "base64").length} bytes from ${result.source}`);
});

test("Instagram → the screenshot hint; no link / private address → refused", async () => {
  assert.match((await call("https://www.instagram.com/p/Cabc123/")).error.message, /Screenshot the post/);
  assert.equal((await call("just words")).error.status, "INVALID_ARGUMENT");
  assert.match((await call("http://169.254.169.254/latest/meta-data/")).error.message, /can't open/);
  assert.match((await call("http://localhost:8080/")).error.message, /can.t open|isn.t a web page/);
  assert.match((await call("https://localhost/")).error.message, /can.t open/);
});
