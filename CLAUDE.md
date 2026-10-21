# Fetch It — project context

Fetch It is our entry for the **PayPal AI Hackathon** on Devpost (https://paypalaihackathon.devpost.com).
Deadline: **Nov 12, 2026, 2:00 PM PT** — we aim to submit **Nov 11**.

**Pitch:** You spot it, we fetch it. Snap a photo (or upload a screenshot, or describe it by voice) of something you
want to buy. An AI shopping agent — a dog mascot named by the user (default "Scout") — identifies it, finds where
to buy it, picks the best option, and pays with PayPal **only within "house rules" the user set**. Anything outside
the rules asks the user first (fingerprint/Face ID approval).

Hackathon requirement: the project must *meaningfully* use PayPal and AI. PayPal is central here: saved PayPal
(vaulting) is what lets the agent pay later on the user's behalf.

Target prizes: Best Use of Agentic Commerce, Best Use of PayPal + AI, Most Creative, Best Demo Delivery,
Channel3 partner prize. Prizes can stack. Judging is split evenly across technical implementation,
design, impact, innovation, and presentation.

---

## Repo layout

```
fetch-it/
├── app/                 Kotlin Multiplatform app (Compose Multiplatform shared UI, Android + iOS)
├── functions/           Firebase Cloud Functions (TypeScript) — the backend
│   ├── spikes/          throwaway experiments (spike1.mjs: photo → Gemini → Channel3)
│   ├── fixtures/        test images; fixtures/out/ = raw spike output (gitignored)
│   └── .env.local       local secrets, read by emulators + spikes, never deployed (gitignored)
├── firestore.rules      Firestore security rules
├── firebase.json        Firebase config (Firestore, Functions, emulators)
├── server/              legacy: Spike 2 PayPal vault scripts (spikes/spike2.mjs) — fold into functions/
├── docs/                screenshots, architecture image, demo notes
├── LICENSE              open-source license (required by hackathon)
└── README.md            setup steps + sandbox logins for judges
```

- Open `app/` in Android Studio. Work on `functions/` from the terminal / Claude Code.
- Firebase project: `fetchit-437a0` (see `.firebaserc`). Emulators: Auth 9090, Functions 5001, Firestore 8080.
- **Secrets live only in `functions/.env.local` (gitignored, never deployed) locally, and in Cloud Secret Manager
  (`defineSecret`) for deployed functions. Never in `app/`, never committed, never in `functions/.env`.**
- The app talks **only to our Functions** (and reads its own Firestore docs). It never calls PayPal, Channel3,
  or Gemini directly.

---

## Architecture

```
KMP app ──Firebase Auth──▶ Cloud Functions (TypeScript)
   │                        ├── Gemini (@google/genai): vision identify + agent loop with function calling
   │                        ├── Channel3 API: product search (text + image similarity), offers, price tracking
   │                        ├── PayPal REST (sandbox): Orders v2, vaulting, shipment tracking, webhooks
   │                        ├── Firestore (Admin SDK): users, house rules, addresses, sizes, activity, watches
   │                        └── FCM: push "Needs your OK" approval requests to the device
   └──read-only──▶ Firestore users/{uid}/…
```

### Backend (Firebase)
- **Cloud Functions** in TypeScript (2nd gen). HTTPS callable/onRequest functions for the app; `onRequest` for
  the PayPal webhook.
- **Firebase Auth** identifies the user; every function checks `request.auth.uid`.
- **Firestore** holds all user data under `users/{uid}/…`.
  **Rules: clients may only read their own `users/{uid}` data; all writes go through Functions** (Admin SDK
  bypasses rules). Nothing else is readable.
- **FCM** for approval notifications ("Needs your OK"). **App Check** later.
- **AI:** Gemini via `@google/genai` on the server only. Flash model for vision + agent loop (check Google's docs
  for the current model id); upgrade if needed.
- **PayPal:** REST via fetch or the PayPal Agent Toolkit (check current npm package name in PayPal docs).
- **Channel3:** REST API / official TS SDK (docs.trychannel3.com).

### Functions (first pass)
| Function | Purpose |
|---|---|
| `identify` | image → item attributes + search query |
| `search` | query/image → ranked offers (filtered by user sizes) |
| `agentTurn` | user message/voice text + context → agent reply, picks, actions |
| `purchase` | buy an offer: rules check → auto-buy (with undo window) or approval request (FCM) |
| `approvePurchase` | user approved (after biometric on device) |
| `undoPurchase` | refund within undo window |
| `setRules` | update house rules (reads come straight from Firestore) |
| `setProfile` | sizes, addresses, dog name |
| `paypalLinkStart` · `paypalLinkComplete` | first-time PayPal approval + vault |
| `paypalWebhook` | verify signature, update activity |

Activity is written by Functions and read by the app directly from Firestore.

### House rules engine — IMPORTANT
Rules are checked in **plain deterministic code before any payment**, never decided by the LLM.
The LLM proposes; the rules engine disposes. Every decision is logged to Activity with the reason.

Rules: monthly budget, per-item auto-buy limit, allowed categories, ask before a new store,
only ship to saved addresses, fingerprint over the limit, ask if unsure about size.

Outcomes: `AUTO_BUY` (with ~5s undo) · `NEEDS_APPROVAL` · `BLOCKED` (nothing charged).

### PayPal flow (sandbox)
1. First run: buyer approves PayPal once (browser sheet), with vaulting so we get a reusable payment token.
   Pull the buyer's shipping address from the approved order to pre-fill "Where should I deliver?".
2. Later purchases: Functions create an order using the vaulted payment method — no redirect.
   Set the shipping preference so PayPal uses the address we provide (user's saved address).
3. Webhooks keep Activity in sync (completed, refunded, reversed). Use shipment tracking for "Arrives Friday".
Verify exact field names in PayPal's Orders v2 docs / AI Toolkit — don't guess.

**Sandbox only.** Base URL `https://api-m.sandbox.paypal.com`. Accounts (US, USD):
- Buyer (personal): `fetchBuyer@fetch.com`
- Merchant (business): `fetchMerchant@fetch.com` — the Fetch It app's sandbox account
- Password is in the team notes, not in this repo.

---

## KMP app (`app/`)
- Compose Multiplatform for all UI. Android first (judges get a signed APK); iOS second.
- Firebase: Auth + Firestore (read-only) + FCM via a KMP Firebase wrapper; calls Functions for everything else.
- Networking: Ktor client + kotlinx.serialization. Images: Coil 3. Navigation: Compose Navigation (multiplatform).
- `expect/actual` only where needed:
  - Camera: CameraX (Android) / AVFoundation (iOS)
  - Image picker: PickVisualMedia / PHPickerViewController
  - Biometrics: BiometricPrompt / LocalAuthentication
  - PayPal approval sheet: Custom Tabs / ASWebAuthenticationSession
  - Voice: SpeechRecognizer / SFSpeechRecognizer (later)
- Functions base URL / emulator host from build config (local emulators vs deployed).

### Screens (design canvas: https://claude.ai/artifact/UE34q9KtegSSACTYXxCo8V)
Onboarding: Welcome → Link PayPal → Set house rules → Where should I deliver? → Your sizes (optional) → Meet your fetcher (name the dog)
Main: Fetch (camera / Upload / Voice) → Look what I found → Refine by voice → Fetched (auto-bought, undo) |
Needs your OK (biometric) → Activity · House rules
Bottom nav: Fetch · Activity · Rules + round "+" (upload).

### Design system — "monochrome scrapbook"
- Colors: background `#F7F7F6`, ink `#111111`, pill gray `#EDEDEB`, muted text `#6E6E6B`,
  paper note `#F1F1EF` with border `#E4E4E1`, accent red `#E8402A` (heart, tongue, collar, warnings only),
  hourglass/warning brown-red `#B4351E`.
- Type: **Gochi Hand** (handwritten titles/captions), **Instrument Serif italic** (numbers, dates, accent words),
  **Figtree** (UI/body).
- Components: black pill buttons (56–60dp tall), gray pill chips, floating bottom nav pill with black active item,
  scalloped-edge product photos, paper-note cards rotated ±1°, polaroids with tape + black pin, dotted background,
  hand-drawn doodles (dog face, heart, thought cloud, hourglass).
- Mascot: ink-line dog with floppy black ears, eye patch, red tongue, red collar with tag.
- Motion: cards rise in with stagger, doodles wobble, dog wags tail, approval sheet slides up,
  fingerprint button pulses, budget bars fill. Respect reduced-motion.

---

## Conventions
- Small commits with clear messages. Keep the README runnable by a stranger.
- Test unhappy paths too: declined card, out of stock in size, refund, rule-blocked purchase.
- No real brands/logos in demo product photos (hackathon rule on third-party trademarks).
- Use the PayPal AI Toolkit commands: `/paypal:doctor`, `/paypal:explain-error`, `/paypal:sandbox`.
  Sandbox access tokens expire ~every 9h → `/paypal:setup refresh` on 401s.

## Status
- [x] PayPal sandbox accounts + app created (US)
- [x] Repo structure: `app/` (KMP template) + `functions/` (Firebase) + secrets in `functions/.env.local`
- [x] Verify sandbox credentials (OAuth token curl) — 200 OK; token has vault payment-tokens readwrite,
      shipping trackers readwrite, webhooks, refund scopes
- [x] Spike 1: photo → vision → Channel3 search; check size variants + stock (`functions/spikes/spike1.mjs`). Learned:
      - Gemini: `models.generateContent` with `responseMimeType: application/json` + `responseJsonSchema` gives strict JSON.
        `gemini-3.8-flash` took 130–230 s/call on our key (even for "Say OK"); `gemini-3.6-flash` ~5–7 s, same answers.
        Flash returns 503 "high demand" at busy times → retry once, then fall back to `gemini-3.5-flash-lite`.
        `gemini-2.5-flash` is closed to new users. `thinkingLevel: MINIMAL` is rejected by 3.8 Flash.
      - Channel3: `POST /v1/search {query, limit}` (~1–3 s) and `POST /v1/image-search {base64_image, limit}`
        (~1 s for a 96 KB JPEG, ~5 s for a 1.2 MB PNG → downscale before upload). Header `x-api-key`. Max limit 30.
        Price is `offers[].price.{price, compare_at_price, currency}`. Defaults to new + InStock.
      - **Sizes:** `variants.options[name=Size].values[]` with `label`, `exists`; `available` is always null on search.
        `GET /v1/products/{id}` (free, ~0.4 s) fills per-size `available` (InStock/OutOfStock/…).
      - **Search price can be stale:** a Nordstrom fleece showed $44.97 in search but $129.50 on detail.
        Always re-fetch product detail before the rules check / purchase.
      - Image search finds look-alikes across categories (jackets, overshirts for a fleece); text search stays
        in-category but generic. Same item often appears as several products (one per colorway) with identical
        title + store. Next: filter/re-rank by Gemini's category, collapse same title + store.
- [x] Spike 2: approve PayPal once with vault, then charge again server-side with no redirect
      (`server/spikes/spike2.mjs`). Learned:
      - Requires **Save payment methods** enabled on the sandbox app, else checkout shows "Something went wrong" after login.
      - Create with `payment_source.paypal.attributes.vault = {store_in_vault: ON_SUCCESS, usage_type: MERCHANT,
        customer_type: CONSUMER}`; approval link is `rel: payer-action`; capture returns
        `payment_source.paypal.attributes.vault.{id, status: VAULTED, customer.id}` and `purchase_units[0].shipping`.
      - Repeat charge: create order with only `payment_source.paypal.vault_id` → returns `COMPLETED` on create
        (no separate capture call). Send a `PayPal-Request-Id` header.
      - Sandbox in a private window if the browser is logged into live paypal.com.
- [ ] Functions skeleton (TypeScript) + Firestore rules + rules engine + deploy
- [ ] App: onboarding → fetch → results → buy/approve → activity
