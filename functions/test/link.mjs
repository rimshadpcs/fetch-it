// PayPal link flow against the emulators + PayPal SANDBOX, as a brand-new user.
//   node test/link.mjs start      → fresh user "link-test", prints the approval URL (approve as the sandbox buyer)
//   node test/link.mjs complete   → completePayPalLink, then shows what was stored (vault token masked)
// Local emulators only.
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";
import { config } from "dotenv";

const PROJECT = "fetchit-437a0";
process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8080";
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "127.0.0.1:9090";
config({ path: new URL("../.env.local", import.meta.url).pathname, quiet: true });
const UID = "link-test";
initializeApp({ projectId: PROJECT });
const auth = getAuth();
const db = getFirestore();

async function idToken() {
  const token = await auth.createCustomToken(UID);
  const r = await fetch("http://127.0.0.1:9090/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fake-api-key", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token, returnSecureToken: true }),
  });
  return (await r.json()).idToken;
}
async function call(name, data, token) {
  const r = await fetch(`http://127.0.0.1:5001/${PROJECT}/us-central1/${name}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ data }),
  });
  return r.json();
}
const mask = (s) => (s ? `${s.slice(0, 3)}…${s.slice(-3)} (${s.length} chars)` : s);

if (process.argv[2] === "start") {
  await db.recursiveDelete(db.collection("users").doc(UID));
  await db.collection("vault").doc(UID).delete();
  await auth.deleteUser(UID).catch(() => {});
  await auth.createUser({ uid: UID, displayName: "Link Test" });
  console.log("fresh user link-test: no vault, no places, paypalLinked unset");
  const r = await call("startPayPalLink", {}, await idToken());
  if (r.error) throw new Error(JSON.stringify(r.error));
  console.log("setupTokenId:", r.result.setupTokenId);
  console.log("return/cancel:", r.result.returnUrl, r.result.cancelUrl);
  console.log("APPROVAL URL:", r.result.approvalUrl);
} else if (process.argv[2] === "complete") {
  const links = await db.collection("paypalLinks").where("uid", "==", UID).where("status", "==", "started").get();
  const setupTokenId = links.docs.map((d) => d.id).at(-1);
  if (!setupTokenId) throw new Error("run `start` first");
  const token = await idToken();
  const r = await call("completePayPalLink", { token: setupTokenId }, token);
  console.log("completePayPalLink →", JSON.stringify(r.result ?? r.error, null, 2));
  console.log("response contains a vault token?", JSON.stringify(r).includes("paypalVaultId") ? "YES (bad)" : "no");
  const vault = (await db.collection("vault").doc(UID).get()).data();
  console.log("vault/link-test:", vault ? { paypalVaultId: mask(vault.paypalVaultId), paypalCustomerId: mask(vault.paypalCustomerId) } : "missing");
  console.log("users/link-test:", (await db.collection("users").doc(UID).get()).data());
  console.log("places/home:", (await db.collection("users").doc(UID).collection("places").doc("home").get()).data());
  const again = await call("completePayPalLink", { token: setupTokenId }, token);
  console.log("replaying the same approval →", again.error?.message);
  const other = await call("completePayPalLink", { token: setupTokenId }, await (async () => {
    await auth.deleteUser("intruder").catch(() => {});
    await auth.createUser({ uid: "intruder" });
    const t = await auth.createCustomToken("intruder");
    return (await (await fetch("http://127.0.0.1:9090/identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=fake-api-key", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ token: t, returnSecureToken: true }) })).json()).idToken;
  })());
  console.log("another user completing it →", other.error?.message);
  const c = await call("confirmPlace", { placeId: "home" }, token);
  console.log("confirmPlace →", JSON.stringify(c.result ?? c.error));
} else {
  console.log("usage: node test/link.mjs start|complete");
}
process.exit(0);
