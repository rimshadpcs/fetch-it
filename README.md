# Fetch It

**You spot it, we fetch it.** Snap a photo, upload a screenshot or share a Pinterest pin of something you want. An AI
shopping agent (a dog you name, "Scout" by default) identifies it, finds where to buy it, picks the best option, and
pays with PayPal, **but only inside the house rules you set**. Anything outside them asks you first (fingerprint);
anything against a hard rule is blocked.

Entry for the [PayPal AI Hackathon](https://paypalaihackathon.devpost.com). License: [MIT](LICENSE).

> **Sandbox only.** Payments use the PayPal sandbox and a demo store. No real money moves and no real orders are placed.

## How PayPal is used (sandbox)

| PayPal API | What it does in Fetch It |
|---|---|
| **Vault v3** (`/v3/vault/setup-tokens`, `/v3/vault/payment-tokens`) | Onboarding: you approve PayPal once; the saved payment token (kept server-side only) lets Scout pay later with no redirect. Nothing is charged; PayPal's shipping address is suggested as your first delivery place. |
| **Orders v2** (`/v2/checkout/orders` with `vault_id`) | Every purchase: paid with the saved PayPal, shipped to one of your saved places (`SET_PROVIDED_ADDRESS`). `PayPal-Request-Id` = the purchase id, so a retried or double-tapped Buy never charges twice. |
| **Payments v2 refunds** (`/v2/payments/captures/{id}/refund`) | Cancel an order before it ships → refunded, budget released. |
| **Order tracking** (`/v2/checkout/orders/{id}/track`) | Demo shipments add a tracker to the PayPal order. |
| **OAuth 2.0** client credentials | Server-to-server auth from Cloud Functions. The app never talks to PayPal directly. |

## How AI is used

| Model / service | What it does |
|---|---|
| **Google Gemini 3.5 Flash-Lite** | Identifies every item in a photo (type, category, colours, bounding box, prominence) as strict JSON → "Which one?" and the crop we search with. Also suggests "goes well with" ideas after a purchase. |
| **Anthropic Claude Haiku 5.5** | Races Gemini from t=0 (strict tool use) so identifying stays ~2 s even when Gemini is slow or down. |
| **Google Gemini 3.6 Flash** | Backup model, and a second opinion when the first answer has low confidence. |
| **Channel3** (sponsor) | AI product search across real stores: image-similarity + text search, product detail (live price, per-size stock, colours), similar products ("More like this", "Find cheaper"), price-tracking history for price watches. |

**The AI proposes; plain code decides.** Before any PayPal call, a deterministic rules engine
([`functions/src/rules.ts`](functions/src/rules.ts), unit tested) checks the monthly budget, per-item limit, allowed
categories, new stores, saved addresses and sizes. Outcomes: **auto-buy** (5 s undo) · **needs your OK**
(fingerprint) · **blocked** (nothing charged). Every decision is logged to Activity with the reason.

## Features

Photo / upload / share-to-app search · "Which one?" for photos with several items · live prices, sizes, colours and
stock · house rules (budget, per-item limit, categories, new store, saved places, size) · auto-buy with undo ·
fingerprint approval · price watches (auto-buy at your target, push on new lows, price-history sparkline) · gifts from a
separate gift budget · saved items · "goes well with" · simulated order tracking with cancel + refund · push
notifications.

## Architecture

```
Android app (Kotlin Multiplatform, Compose) ──Firebase Auth──▶ Cloud Functions (TypeScript, Node 22)
   │                                                          ├── Gemini + Claude: identify
   │                                                          ├── Channel3: product search, prices, stock
   │                                                          ├── PayPal REST (sandbox): vault, orders, refunds, tracking
   │                                                          ├── Firestore: rules, places, purchases, activity, watches
   │                                                          └── FCM: "Needs your OK" / price-drop pushes
   └──read-only──▶ Firestore users/{uid}/…
```

- `app/`: Kotlin Multiplatform app (Compose Multiplatform; Android first, iOS not finished)
- `functions/`: Firebase Cloud Functions, the backend
- `firestore.rules`: clients read only their own data; all writes go through Functions
- `hosting/`: privacy policy and account-deletion pages
- Other tech: Firebase (Auth, Firestore, Functions, Hosting, Cloud Messaging), Ktor, kotlinx.serialization, Coil 3

## Try it (judges)

1. Install the APK from the Devpost page (or the Play Store open test).
2. **Link PayPal** with the PayPal **sandbox** test buyer given on the Devpost page (real PayPal accounts don't work
   in the sandbox).
3. Confirm the address, set house rules, name your dog, then photograph or upload any object (a lamp works well) and
   tap **Buy**.

## Run it yourself

You need: Node 22, Java 21, Android Studio, a Firebase project, a PayPal sandbox app (with **Save payment methods**
enabled), and API keys for Gemini, Anthropic and Channel3.

**Backend**

```bash
cd functions
npm install
npm run build
cd ..
npx firebase-tools@15 emulators:start --only functions,firestore,auth
```

`functions/.env.local` (never committed) holds `GEMINI_API_KEY`, `ANTHROPIC_API_KEY`, `CHANNEL3_API_KEY`,
`PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET` and `FETCH_SIGNING_KEY` (any long random string). Deployed functions read
the same names from Cloud Secret Manager: see [DEPLOY.md](DEPLOY.md).

Tests: `npm test` (rules engine, ranking, identify race, addresses…), `npm run test:rules` (Firestore rules, emulator),
plus end-to-end scripts in `functions/test/` that run against the emulators and the PayPal sandbox.

**App**

1. Add your Firebase Android config as `app/androidApp/google-services.json` (gitignored).
2. Open `app/` in Android Studio and run `androidApp`, or `cd app && ./gradlew :androidApp:installDebug`.

## Credits

- Icons: [Oreo Doodle Icons](https://github.com/oreo-design/doodle-icons) by Nicole Tang (free set, MIT).
- Fonts: Gochi Hand, Instrument Serif, Figtree (SIL Open Font License 1.1).

Full notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
