// Saved places: addPlace / deletePlace / setDefaultPlace, and the rules engine still refusing unsaved places.
// No paid APIs, no PayPal. Run with emulators up: node --test test/places.mjs   (or npm run test:places)
import assert from "node:assert/strict";
import { test } from "node:test";
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";

const PROJECT = "fetchit-437a0";
process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8080";
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "127.0.0.1:9090";
const FN = (name) => `http://127.0.0.1:5001/${PROJECT}/us-central1/${name}`;
const UID = "places-test";

initializeApp({ projectId: PROJECT });
const auth = getAuth();
const db = getFirestore();
const user = db.collection("users").doc(UID);
const places = user.collection("places");

await db.recursiveDelete(user);
await auth.deleteUser(UID).catch(() => {});
await auth.createUser({ uid: UID });
const { idToken } = await (
  await fetch("http://127.0.0.1:9090/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fake-api-key", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token: await auth.createCustomToken(UID), returnSecureToken: true }),
  })
).json();

async function call(name, data) {
  const res = await fetch(FN(name), {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${idToken}` },
    body: JSON.stringify({ data }),
  });
  return res.json();
}
const addr = { fullName: "Sam Rivera", line1: "2211 N First St", city: "San Jose", state: "CA", postalCode: "95131" };
const defaults = async () => (await places.get()).docs.filter((d) => d.data().isDefault).map((d) => d.id);

let home, work;

test("PayPal suggestion, then 'Use a different address': the manual place replaces the suggestion and is the default", async () => {
  await places.doc("home").set({ label: "Home", ...addr, countryCode: "US", suggested: true, isDefault: true });
  const { result, error } = await call("addPlace", { label: "Home", ...addr, line1: "500 Castro St", city: "Mountain View", postalCode: "94041", phone: "650 555 0101" });
  assert.equal(error, undefined, JSON.stringify(error));
  home = result.placeId;
  assert.equal(result.isDefault, true);
  assert.equal((await places.doc("home").get()).exists, false, "the unconfirmed suggestion is gone");
  const doc = (await places.doc(home).get()).data();
  assert.deepEqual([doc.suggested, doc.source, doc.phone, doc.countryCode], [false, "manual", "+16505550101", "US"]);
});

test("bad addresses come back with every field's problem; nothing is saved", async () => {
  const before = (await places.get()).size;
  const { error } = await call("addPlace", { label: "Work", fullName: "Sam", line1: "Main St", city: "SJ", state: "ZZ", postalCode: "123" });
  assert.equal(error?.status, "INVALID_ARGUMENT");
  assert.deepEqual(Object.keys(error.details.fields).sort(), ["line1", "postalCode", "state"]);
  assert.equal((await places.get()).size, before);
});

test("a second place with its own label; duplicate labels are refused", async () => {
  const { result } = await call("addPlace", { label: "Work", ...addr, line1: "1 Market St", city: "San Francisco", postalCode: "94105" });
  work = result.placeId;
  assert.equal(result.isDefault, false);
  assert.equal((await call("addPlace", { label: "work", ...addr })).error?.status, "ALREADY_EXISTS");
});

test("edit keeps the id; setDefaultPlace moves the default", async () => {
  const { result } = await call("addPlace", { placeId: work, label: "Office", ...addr, line1: "1 Market St", line2: "Floor 3", city: "San Francisco", postalCode: "94105" });
  assert.equal(result.placeId, work);
  assert.equal((await places.doc(work).get()).data().line2, "Floor 3");
  await call("setDefaultPlace", { placeId: work });
  assert.deepEqual(await defaults(), [work]);
});

test("deleting the default needs a new default; watches move with it; the only place can't be deleted", async () => {
  await user.collection("watches").doc("w1").set({ placeId: work, status: "watching" });
  const refused = await call("deletePlace", { placeId: work });
  assert.equal(refused.error?.status, "FAILED_PRECONDITION");
  assert.equal(refused.error.details.needsNewDefault, true);
  const { result, error } = await call("deletePlace", { placeId: work, newDefaultId: home });
  assert.equal(error, undefined, JSON.stringify(error));
  assert.equal(result.defaultPlaceId, home);
  assert.deepEqual(await defaults(), [home]);
  assert.equal((await user.collection("watches").doc("w1").get()).data().placeId, home);
  assert.equal((await call("deletePlace", { placeId: home })).error?.status, "FAILED_PRECONDITION");
});

test("an unknown place id is not found", async () => {
  const other = await call("setDefaultPlace", { placeId: "nope" });
  assert.equal(other.error?.status, "NOT_FOUND");
});
