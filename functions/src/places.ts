// Saved delivery places (users/{uid}/places/{placeId}). PayPal's address arrives as a *suggested* place
// (paypalLink.ts) that only counts once confirmed; these callables add, edit, delete and pick the default.
// The rules engine's "only ship to saved places" check reads the same docs (suggested ones never count).

import { HttpsError, onCall } from "firebase-functions/v2/https";
import { FieldValue } from "firebase-admin/firestore";
import { ENFORCE_APP_CHECK } from "./options.js";
import { db, userDoc } from "./lib/db.js";
import { requireUid } from "./purchase/purchase.js";
import { validatePlace, type PlaceInput } from "./lib/address.js";

export const MAX_PLACES = 10;

const placeIdOk = (v: unknown): v is string => typeof v === "string" && /^[A-Za-z0-9_-]{1,40}$/.test(v);
const places = (uid: string) => userDoc(uid).collection("places");

/**
 * Add a place (no placeId) or edit one (placeId). Saved as confirmed: it counts for "only ship to saved places"
 * right away. Adding a place clears any PayPal suggestion still waiting for a yes (the user chose another
 * address). The first place, or `makeDefault`, becomes the default. Labels are unique per user.
 */
export const addPlace = onCall<PlaceInput & { placeId?: string; makeDefault?: boolean }>({ cors: true, enforceAppCheck: ENFORCE_APP_CHECK }, async (request) => {
  const uid = requireUid(request.auth?.uid);
  const d = request.data ?? {};
  if (d.placeId !== undefined && !placeIdOk(d.placeId)) throw new HttpsError("invalid-argument", "placeId is invalid.");
  const v = validatePlace(d);
  if ("errors" in v) throw new HttpsError("invalid-argument", Object.values(v.errors)[0], { fields: v.errors });

  return db.runTransaction(async (tx) => {
    const all = await tx.get(places(uid));
    const saved = all.docs.filter((p) => !p.data().suggested);
    const editing = d.placeId ? all.docs.find((p) => p.id === d.placeId) : undefined;
    if (d.placeId && (!editing || editing.data().suggested)) throw new HttpsError("not-found", "No such saved place.");
    if (!editing && saved.length >= MAX_PLACES) throw new HttpsError("resource-exhausted", `Up to ${MAX_PLACES} places.`);
    const clash = saved.find((p) => p.id !== d.placeId && String(p.data().label).toLowerCase() === v.place.label.toLowerCase());
    if (clash) throw new HttpsError("already-exists", `You already have a place called ${clash.data().label}.`, { fields: { label: "Pick another name" } });

    const ref = editing ? editing.ref : places(uid).doc();
    const others = saved.filter((p) => p.id !== ref.id);
    const makeDefault = d.makeDefault === true || !others.some((p) => p.data().isDefault);
    if (makeDefault) others.filter((p) => p.data().isDefault).forEach((p) => tx.update(p.ref, { isDefault: false }));
    if (!editing) all.docs.filter((p) => p.data().suggested).forEach((p) => tx.delete(p.ref));

    const doc = { ...v.place, suggested: false, isDefault: makeDefault || (editing?.data().isDefault ?? false), updatedAt: FieldValue.serverTimestamp() };
    if (editing) tx.update(ref, doc);
    else tx.set(ref, { ...doc, source: "manual", createdAt: FieldValue.serverTimestamp(), confirmedAt: FieldValue.serverTimestamp() });
    return { placeId: ref.id, ...v.place, isDefault: doc.isDefault };
  });
});

/**
 * Delete a saved place. Deleting the default needs `newDefaultId` (another saved place) when others exist; the
 * only place can't be deleted (with "only ship to saved places" on, nothing could ship). Price watches that
 * shipped there move to the (new) default.
 */
export const deletePlace = onCall<{ placeId: string; newDefaultId?: string }>({ cors: true, enforceAppCheck: ENFORCE_APP_CHECK }, async (request) => {
  const uid = requireUid(request.auth?.uid);
  const { placeId, newDefaultId } = request.data ?? {};
  if (!placeIdOk(placeId)) throw new HttpsError("invalid-argument", "placeId is required.");
  if (newDefaultId !== undefined && (!placeIdOk(newDefaultId) || newDefaultId === placeId)) throw new HttpsError("invalid-argument", "newDefaultId must be another place.");

  const movedTo = await db.runTransaction(async (tx) => {
    const all = await tx.get(places(uid));
    const target = all.docs.find((p) => p.id === placeId);
    if (!target) throw new HttpsError("not-found", "No such place.");
    if (target.data().suggested) { tx.delete(target.ref); return null; } // declining a PayPal suggestion
    const others = all.docs.filter((p) => p.id !== placeId && !p.data().suggested);
    if (!others.length) throw new HttpsError("failed-precondition", "Add another place before deleting your only one.");
    let fallback = others.find((p) => p.data().isDefault);
    if (target.data().isDefault) {
      fallback = others.find((p) => p.id === newDefaultId);
      if (!fallback) throw new HttpsError("failed-precondition", "That's your default place: choose a new default first.", { needsNewDefault: true });
      tx.update(fallback.ref, { isDefault: true });
    }
    tx.delete(target.ref);
    return fallback?.id ?? others[0].id;
  });

  if (movedTo) {
    const watches = await userDoc(uid).collection("watches").where("placeId", "==", placeId).get();
    await Promise.all(watches.docs.map((w) => w.ref.update({ placeId: movedTo })));
  }
  return { deleted: placeId, defaultPlaceId: movedTo };
});

/** Make a saved place the default (Buy starts there; price-watch buys ship there). */
export const setDefaultPlace = onCall<{ placeId: string }>({ cors: true, enforceAppCheck: ENFORCE_APP_CHECK }, async (request) => {
  const uid = requireUid(request.auth?.uid);
  const placeId = request.data?.placeId;
  if (!placeIdOk(placeId)) throw new HttpsError("invalid-argument", "placeId is required.");
  await db.runTransaction(async (tx) => {
    const all = await tx.get(places(uid));
    const target = all.docs.find((p) => p.id === placeId);
    if (!target || target.data().suggested) throw new HttpsError("not-found", "No such saved place.");
    all.docs.forEach((p) => { if (p.data().isDefault !== (p.id === placeId)) tx.update(p.ref, { isDefault: p.id === placeId }); });
  });
  return { defaultPlaceId: placeId };
});
