import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";

if (!getApps().length) initializeApp();

export const db = getFirestore();

/*
 * Firestore layout (clients may only read users/{uid}/**; every write goes through Functions):
 *   users/{uid}/settings/rules      HouseRules
 *   users/{uid}/places/{placeId}    saved addresses: label, fullName, line1, line2, city, state, postalCode, countryCode
 *   users/{uid}/stores/{merchant}   stores bought from before (for ask-before-new-store)
 *   users/{uid}/spend/{YYYY-MM}     month-to-date spend incl. reserved auto-buys: { totalCents, currency }
 *   users/{uid}/purchases/{id}      purchase state machine (see purchase.ts)
 *   users/{uid}/activity/{id}       one entry per decision/event, shown in the Activity tab
 *   vault/{uid}                     PayPal vault token — not under users/, so the app can never read it
 */
export const userDoc = (uid: string) => db.collection("users").doc(uid);
export const monthKey = (d = new Date()) => d.toISOString().slice(0, 7);
