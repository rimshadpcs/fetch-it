// Firestore rules check. Run with emulators up: npm run test:rules (local emulator only, throwaway users).
const AUTH = "http://127.0.0.1:9090/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-api-key";
const FS = "http://127.0.0.1:8080/v1/projects/fetchit-437a0/databases/(default)/documents";
const signUp = async () => (await (await fetch(AUTH, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ returnSecureToken: true }) })).json());
const a = await signUp(), b = await signUp();
const req = (method, path, token, body) => fetch(`${FS}/${path}`, { method, headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" }, body: body && JSON.stringify(body) }).then(r => r.status);
const doc = { fields: { budget: { integerValue: "200" } } };
await req("PATCH", `users/${a.localId}/rules/house`, "owner", doc);   // admin seed (bypasses rules)
await req("PATCH", `other/x`, "owner", doc);
await req("PATCH", `vault/${a.localId}`, "owner", { fields: { paypalVaultId: { stringValue: "test" } } });
const checks = [
  ["owner reads own doc", await req("GET", `users/${a.localId}/rules/house`, a.idToken), 200],
  ["other user reads it", await req("GET", `users/${a.localId}/rules/house`, b.idToken), 403],
  ["owner writes own doc", await req("PATCH", `users/${a.localId}/rules/house`, a.idToken, doc), 403],
  ["owner creates in own tree", await req("PATCH", `users/${a.localId}/activity/new`, a.idToken, doc), 403],
  ["user reads other collection", await req("GET", `other/x`, a.idToken), 403],
  ["user reads vault/{uid} (PayPal token)", await req("GET", `vault/${a.localId}`, a.idToken), 403],
  ["unauthenticated read", await fetch(`${FS}/users/${a.localId}/rules/house`).then(r => r.status), 403],
];
for (const [name, got, want] of checks) console.log(got === want ? "PASS" : "FAIL", name, got);
if (checks.some(([, got, want]) => got !== want)) process.exit(1);
