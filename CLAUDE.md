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
│   ├── src/             function code (index.ts, fetch/ pipeline, lib/ gemini + channel3 + image) → built to lib/
│   ├── test/            emulator checks: rules.mjs (`npm run test:rules`), fetchItem.mjs
│   ├── spikes/          throwaway experiments (spike1.mjs: photo → Gemini → Channel3)
│   ├── fixtures/        test images (unbranded only); fixtures/out/ = raw spike output (gitignored)
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
  Run them with `npm run build` in `functions/`, then `npx firebase-tools@15 emulators:start --only functions,firestore,auth`
  from the repo root (the global CLI 13.x predates Node 22 functions). `npm run build:watch` hot-reloads.
- Functions: ESM TypeScript, Node 22, firebase-functions 7, firebase-admin 14, region us-central1.
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
- **AI:** on the server only. Identify races Gemini `gemini-3.5-flash-lite` (minimal thinking, preferred) against
  Claude `claude-haiku-5-5` (`@anthropic-ai/sdk`, strict tool use), both from t=0 — see Status → identify race.
- **PayPal:** REST via fetch or the PayPal Agent Toolkit (check current npm package name in PayPal docs).
- **Channel3:** REST API / official TS SDK (docs.trychannel3.com).

### Functions (first pass)
| Function | Purpose |
|---|---|
| `health` | liveness check (onRequest) |
| `fetchItem` ✅ | photo → items in it ("Which one?") → top 5 offers for one item with live price, colour, sizes, stock (callable, streams) |
| `agentTurn` | user message/voice text + context → agent reply, picks, actions |
| `purchaseOffer` ✅ | `{offerId, size?, placeId, requestId}` (requestId = one per Buy tap = purchase id; a repeat returns the same purchase and waits for its outcome) → live price → rules → BLOCKED / NEEDS_APPROVAL (FCM placeholder) / AUTO_BUY (streams decision, 5 s undo, then vault charge) |
| `approvePurchase` ✅ | `{purchaseId, allowCategory?}` after the fingerprint: re-checks live price + hard rules, charges right away; allowCategory adds the item's root category to the rules |
| `undoPurchase` ✅ | `{purchaseId}`: undo an auto-buy inside the window (nothing charged) or decline an approval |
| `watchOffer` ✅ | `{offerId, targetPrice, size?, placeId?}` → `users/{uid}/watches/{id}` + Channel3 price tracking; one live watch per item (again = new target, startPrice kept) |
| `stopWatch` ✅ | `{watchId}`: a watching watch → `stopped` + activity; anything else refused |
| `checkWatches` ✅ | scheduled hourly: live price ≤ target → "Price dropped" activity → same purchase path (rules apply) |
| `checkWatchesNow` ✅ | callable "check now" for the caller's own watches (how the emulator test runs a check) |
| `findSimilar` ✅ | `{offerId, maxPrice?}`: "More like this" / "Find cheaper" via Channel3 `/v1/similar`, signed offers |
| `setRules` | update house rules (reads come straight from Firestore) |
| `registerPushToken` ✅ | `{token, platform}` → `users/{uid}/devices/{sha256(token)[:32]}`; dead tokens are removed when FCM rejects them |
| `setProfile` ✅ | `{sizes?: {top, waist, shoe, fit}, askIfUnsure?, dogName?}` → `users/{uid}/profile/main` (only the fields sent; null clears a size); ask-if-unsure mirrored with the rules doc both ways |
| `startPayPalLink` ✅ | onboarding: Vault v3 setup token → approval URL; returns to `fetchit://paypal/return` / `…/cancel` |
| `completePayPalLink` ✅ | `{token}` (setup token id): vault it, store token in `vault/{uid}` only, suggest PayPal's shipping address as Home, `users/{uid}.paypalLinked = true`. Nothing charged |
| `confirmPlace` ✅ | `{placeId}`: user confirms a suggested place (suggested places don't count as saved until then); becomes the default unless another saved place is |
| `addPlace` ✅ | `{label, fullName, line1, line2?, city, state, postalCode, phone?, placeId?, makeDefault?}`: US-validated (`lib/address.ts`; errors per field in `details.fields`), saved confirmed; placeId = edit; adding clears an unconfirmed PayPal suggestion; first place / makeDefault → default; labels unique; max 10 |
| `deletePlace` ✅ | `{placeId, newDefaultId?}`: deleting the default needs `newDefaultId`; the only place can't go; its price watches move to the default |
| `setDefaultPlace` ✅ | `{placeId}`: the place Buy starts with and watch buys ship to |
| `saveItem` ✅ | `{offerId, saved}` heart / un-heart a result (or `{savedId, saved: false}` from the Saved list) → `users/{uid}/saved/{sha256(product|store|colour)[:24]}` with the live price; max 100 |
| `savedOffer` ✅ | `{savedId}` → fresh signed offer (same store + colour) with live price, stock, sizes: buying a saved thing goes through the normal purchase path |
| `advanceOrders` ✅ | scheduled every minute: demo orders placed → confirmed (30 s) → shipped (2 min, PayPal tracking) → delivered (promised date) |
| `cancelOrder` ✅ | `{orderId}` before shipping: refund the sandbox capture, `cancelled`, roll back month-to-date spend |
| `sweepStuckPurchases` ✅ | every 5 min: purchases stuck > 2 min in pending_undo/approved/charging → charge (idempotent) or fail |
| `advanceOrder` · `runMaintenance` 🐞 | debug only (emulator or `ALLOW_DEBUG=true`): fast-forward an order; run both sweeps now |
| `paypalWebhook` | verify signature, update activity |

Activity is written by Functions and read by the app directly from Firestore.

### House rules engine — IMPORTANT
Rules are checked in **plain deterministic code before any payment**, never decided by the LLM.
The LLM proposes; the rules engine disposes. Every decision is logged to Activity with the reason.

Rules: monthly budget, per-item auto-buy limit, allowed categories, ask before a new store,
only ship to saved addresses, fingerprint over the limit, ask if unsure about size.

Outcomes: `AUTO_BUY` (with ~5s undo) · `NEEDS_APPROVAL` · `BLOCKED` (nothing charged).

`functions/src/rules.ts` → `decide()` returns the decision plus every check as `{rule, pass, onFail, message}`
("$54 over your $75 limit"). Unit tests: `npm test` (`test/rules.test.mjs`).
| Check | Fails → |
|---|---|
| PayPal linked · in stock / still listed (live price) · currency · monthly budget (hard cap, counts reserved auto-buys) · saved place · requested size missing / sold out | BLOCKED |
| category not in the allowed list · per-item limit · new store · size not given (ask-if-unsure) · price went up since approval | NEEDS_APPROVAL (approval overrides these, never a block) |

Categories are Channel3 slugs; an offer passes if any slug on its path is allowed. The app offers the 16 Channel3 roots
(`ROOT_CATEGORIES` in rules.ts / `ShopCategory`; e.g. perfume = health-beauty > personal-care > cosmetics >
perfume-cologne). Older rules may hold sub-slugs (clothing, shoes, books: real; gift-cards: never existed); they still
match and show as extra chips. `approvePurchase {allowCategory: true}` also adds the item's root category to the rules
in the same transaction and logs a `rules_updated` activity entry ("Always allow Health & Beauty" on the approval sheet).

### Data (Firestore)
`users/{uid}/settings/rules` (HouseRules) · `places/{placeId}` (label, fullName, line1, line2, city, state,
postalCode, countryCode) · `stores/{merchant}` · `spend/{YYYY-MM}` {total} · `purchases/{id}` · `activity/{id}`.
`watches/{id}` (status watching → triggered, target, last price, purchase id).
`profile/main` {sizes {top XS–XXXL, waist 24–48, shoe "US 9"/"UK 8"/"EU 42", fit}, askIfUnsure, dogName}: the size rule
uses it when no size is sent (`profileSize`: shoes → shoe size, US sizing only; bottoms → waist; other clothing → top).
PayPal vault token lives in top-level **`vault/{uid}`**, which clients can't read (rules test covers it).
Spend is stored as integer `totalCents` (float increments drift). The watches collection-group query needs
the field override in `firestore.indexes.json`.
Credit protection (`functions/src/limits.ts`), top-level so clients can't read them: **`limits/{uid}`** = per-action
fixed windows `{hour, hourCount, day, dayCount}` (UTC); **`usage/{YYYY-MM-DD}`** = global calls to gemini / claude /
channel3 (+ `tripped.<service>` timestamp when the daily cap was hit).

**Simulated fulfilment** (`functions/src/fulfillment.ts`): after a successful capture, the completion transaction
creates `users/{uid}/orders/{purchaseId}` (`simulated: true`, `retailerOrderId: DEMO-12345`, items, `totalCents`,
shipTo, `promisedDeliveryDate`, PayPal order + capture ids, `statusHistory`). Timings from params
`FULFILLMENT_CONFIRM_AFTER_SECONDS` (30), `FULFILLMENT_SHIP_AFTER_SECONDS` (120), `FULFILLMENT_DELIVERY_DAYS` (3).
"Shipped" uses carrier `OTHER` + `carrier_name_other: "Fetch It Demo Post"` and a `DEMO…` tracking number, pushed
to PayPal with `POST /v2/checkout/orders/{id}/track {capture_id, tracking_number, carrier, carrier_name_other,
notify_payer: false}` (tracker id = `{captureId}-{trackingNumber}`). Cancel = `POST /v2/payments/captures/{id}/refund`
with `PayPal-Request-Id: refund-{orderId}`. Every status change writes an `order` activity entry.

**Demo store:** charges go to our PayPal sandbox merchant and no order is placed with the real store. Every
purchase and purchase activity entry carries `merchantOfRecord: "Fetch It demo store (PayPal sandbox)"`,
`retailer` (the Channel3 store domain) and `retailerOrderStatus: "simulated"`; the app shows
"Demo store · sandbox" from these.
Purchase states: `blocked` · `needs_approval → approved|declined` · `pending_undo → undone|charging` ·
`charging → completed|failed`. An auto-buy/approval reserves its price in `spend` inside the decision
transaction (no double-spend races); undo/decline/failure releases it. Every transition writes an activity
entry (status, summary, reasons, title, merchant, price, image, PayPal order id, timestamps).

### Signed tokens (`functions/src/lib/sign.ts`, HMAC-SHA256 with `FETCH_SIGNING_KEY`)
- `itemsToken` from fetchItem = the detected items, bound to uid + sha256 of the photo, 24 h. The second
  call sends `{imageBase64, itemsToken, itemIndex}` (items aren't echoed, so app JSON re-encoding can't break it).
- `offerId` on each offer = {productId, merchant, colour, title, image} bound to uid, 24 h. Never a price.

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
Onboarding: Welcome → Link PayPal → Where should I deliver? (PayPal's address "from PayPal": Yes, deliver here / Use a
different address → form) → Set house rules → Your sizes (optional) → Meet your fetcher (name the dog)
Main: Fetch (camera / Upload / Voice) → Look what I found → Refine by voice → Fetched (auto-bought, undo) |
Needs your OK (biometric) → Activity · You
Bottom nav (`ui/Nav.kt`, drawn once in `App` outside the screen switcher): Activity · **Fetch** (80 dp black circle
sitting a third out of the bar, ringed in white, camera doodle; no ears; tapping it on Fetch opens the camera) · You (user doodle). No "+" (Fetch has Upload). The light
pill slides between Activity and You; Activity has a red dot while a purchase needs your OK (`AppState.needsApproval`).
You (former Account): house rules summary → Rules, then PayPal, saved places, sizes, fetcher's name (long-press: Motion
lab), notifications, privacy, help, sign out. Rules is a pushed screen: from You, the budget chip on Fetch, or "Change
rule" on the blocked outcome / approval sheet / Activity (`changeRuleTarget`: saved_place → Places, else
`Screen.Rules(RuleFocus)` scrolls to that section and outlines it).

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

### Motion kit (`shared/.../ui/motion/`, Compose only)
Rules: one-shot animations ≤ ~600 ms (only Scout's idle loops run longer), nothing blocks taps beyond the Buy guard,
`LocalReduceMotion` (system "Remove animations" / iOS Reduce Motion, provided in `App`) → every piece shows its end
state at once (haptics still play). Hidden **Motion lab** (long-press the dog on Account) shows every piece with Replay.
- `rememberProgress` (0→1 one-shot) · `rememberClock` (frame clock read in draw lambdas: loops redraw, no recomposition)
- `PathDraw` + doodle paths (`wobblyLoop`, `doodleArrow`, `doodleCross`, `outlineBox`, `drawTruck`, `drawFingerprint`):
  draws the first N% of the paths' length (PathMeasure segments), optional dashes
- `HandwritingReveal` (left-to-right clip, ~40 ms/letter, ≤ 600 ms) · `Modifier.pinIn(index)` (drop, overshoot,
  settle at a seeded tilt, 70 ms stagger) · `Stamp` (1.6→1 spring, then `ScreenShake` + haptic) · `CountUpMoney`
- `Dog(size, DogMood)`: Idle, Wag, Sniffing, Excited, HeadShake, Sleeping (+ `bone`), `RunningDog`, `ScoutRunsIn`; all one
  pose-based drawing of the design-canvas dog (`drawDog(DogPose)`), so every mood is recognisably Scout.
- `Haptics`: stamp, undo, approve, block, tap, tick, success, toggle · `Modifier.sharedPhoto(key)`: shared element across
  screens (`SharedTransitionLayout` around the screen `AnimatedContent`).
- Entrances (`Entrance.kt`): `riseIn`, `popIn`, `noteSlideIn`, `wiggleAfterIdle`, `Tape`, `pageTurn`. `uiSpring()` =
  dampingRatio 0.65 (rules: taps 80–150 ms, transitions 250–400 ms, celebrations ≤ 600 ms, springs 0.6–0.7).
  Under reduce motion entrances and screen switches become plain fades (`rememberProgress(fadeOnly = true)`).

### Icons (`shared/.../ui/icons/DoodleIcons.kt`)
Oreo Doodle Icons, free set (MIT, Nicole Tang; THIRD_PARTY_NOTICES.md). SVG paths copied verbatim into `enum Doodle`
→ stroke-only `ImageVector` on the 48 grid, round caps/joins, static (no draw-on, no wiggle). `DoodleIcon(icon, cd,
size, color, strokeWidth)`: ~4 grid units at 24 dp (set default 3.4), 4.6 when active; #111111 active / #6E6E6B
inactive. No Material icons. Adding one: copy its `<path d>` values from `npm pack @oreo-design/doodle-icons`.
One exception, asked for Nov 7: the Fetch button's camera is animated (`BoilingCamera` in icons/BoilingDoodle.kt): its
strokes draw on with the set's animated-SVG timings, then it "boils" forever (points displaced by smooth noise whose
seed steps 6 times per 0.86 s). The noise is the SVG's own feTurbulence ported from the SVG 1.1 spec (`SvgTurbulence`,
fractalNoise 0.055, 2 octaves, seeds 1–6); stroke points are moved where feDisplacementMap scale 4 shows them (inverse,
6 fixed-point steps), stroke 3.4. Checked against headless Chrome's render of the SVG: per-pixel port matches, the drawn
line sits within ~0.1 grid unit. The filter also thins/thickens strokes a little, which a moved line can't. Static under
reduce motion.

### Components (`shared/.../ui/components/`)
- `Modifier.pressScale(source, Press.PILL 0.96 | GRAY 0.95 | ROUND 0.90, tilt)`: 80 ms sink, spring back with
  overshoot; round buttons also turn ±6° once (`Modifier.roundButton`, `Modifier.grayPress`). Visual only, never
  delays a click. Buy keeps its own armed gesture and feeds presses into the same effect.
- `PillButton(…, loading, done)`: shadow tightens on press + light haptic; loading = dashed spinner; done = pen ✓ in
  300 ms + success haptic, caller moves on ~350 ms later (`DONE_PAUSE_MS`); disabled→enabled fades 0.4→1 with an
  arming bounce.
- `Segmented` (sliding white pill, color crossfade, tick haptic) · `Chip(check = true)` for multi-select (pop 1.08→1 +
  drawn ✓; deselect shrinks and fades) · `Toggle`/`PawSwitch` (spring knob, track fill, paw print for 600 ms on "on") ·
  `SizePicker` (pen circle around the pick; `flipKey` flips the labels US→UK→EU) · `AmountField` (typed whole dollars,
  CountUp on change, underline black while focused, warning slides down with a pulsing red dot).
- Onboarding: steps turn like pages (slide + 3° swing, progress segment fills), Welcome polaroids drop and get taped,
  headline writes on word by word; Link PayPal ticks draw one by one, "LINKED ✓" stamps in and Scout hops on return;
  the address note slides in and a pin drops on confirm; rules chips pop in, the per-item bar fills; size rows rise,
  Skip wiggles after 5 s idle; Scout pops in, tilts his head while you type his name, runs off on Start fetching, and
  the camera screen slides up.

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
- [x] `functions/` converted to TypeScript on Node 22; `firestore.rules`: users/{uid}/** readable by that user only,
      all client writes denied, everything else denied (`npm run test:rules`, 6 checks)
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
        title + store.
- [x] `fetchItem` callable (`functions/src/fetch/`). Auth required. Pipeline:
      resize (1024 px for Gemini, 768 px for Channel3) → **in parallel** Gemini identify + Channel3 image search →
      text search as soon as identify returns → filter both lists to target categories → RRF merge, dedupe by
      product id and normalized title + merchant (same title at one store = colourways, kept as `colors`) →
      product detail for the top 5 → offers with **detail price only**, per-size stock, colours, timings.
      Streams `image_matches` → `identified` → `offers` when the client asks for streaming.
      Lamp in emulator: 3.1–6.1 s end to end (median ~3.5 s; Gemini 1.7–2.2 s; slow runs = Channel3 variance).
      Learned while tuning:
      - Gemini Flash-Lite time is almost all time-to-first-token (1.5–5 s, random). Streaming saves ~150 ms → not
        worth it. `mediaResolution: MEDIUM` halves image tokens (1100 → 543) with the same answers. Hedge: if no
        answer in 2.5 s, send a second Flash-Lite call and take the first; on 429/5xx go to `gemini-3.6-flash`.
        3.5 Flash-Lite accepts `thinkingLevel: MINIMAL`; `thinkingBudget: 0` is rejected.
      - Channel3 `limit` matters most: limit 10 ≈ 1.1 s steady; limit 20 swings 1.4–3.6 s. Image search at
        768 px ≈ 1.3 s vs ~3.5 s at 1024 px (7/10 same top matches; 512 px no faster, 4/10).
      - Channel3 caches: repeated queries/details come back much faster, so benchmark with fresh photos.
      - Parallel product-detail calls slow down uncached (5 at once ≈ 1.4 s vs 0.35 s for one). We prefetch details
        for the top image matches while Gemini runs (usually 2 of the final 5 hit the cache).
      - Products carry `colors: [{hex, percentage}]` (dominant photo colours) → swatches for colourway options.
      - Send `x-user-id` (Firebase uid) so buy links/commissions are attributed per user.
      - Category filter: Gemini's free-text category ≠ Channel3 slugs, so targets = slugs whose name shares a word
        with Gemini's item_type/category + slugs that dominate the text-search top 10.
      - Clothing (brown oversized hoodie, `fixtures/hoodie.jpg`, kept out of git): 5.7 s uncached, 3.9 s cached.
        Gemini picked the hoodie over the cap/trousers in the frame. All 5 offers in `hoodies-3`; 4/5 had
        per-size stock (e.g. Gap XS–XXXL InStock), colours came back as named options (Truffle, palomino brown…).
        Image-search and text-search hits didn't overlap, so ranking interleaves the two lists.
      - **Sizes depend on the selected colour.** Detail returns the default colour's matrix: talentless.co's default
        "Coco" (brown, the matching colour) has every size `exists: false`, while `?option_Color=Cement` gives
        M–XXL InStock. Size labels vary by store (S/M/L vs SML/MED/LRG).
- [x] fetchItem v2: real-world photos (people, busy backgrounds, several items, screenshots).
      **API:** `{imageBase64, itemIndex?, itemsToken?}` →
      `status: "choose"` (several items: show "Which one?", then call again with `itemIndex` + the signed
      `itemsToken` it returned, which skips Gemini) · `"results"` (one item, or the chosen one) · `"none"`.
      Stream events: `items` → `image_matches` → `offers`. The photo is only held in memory for the request;
      only a padded crop of the chosen item goes to Channel3.
      - Gemini returns **all** items (max 6, most prominent first) with `box_2d` [ymin,xmin,ymax,xmax] on 0–1000
        → returned as 0–1 `{x,y,width,height}`; boxes checked visually and are tight. Auto-continue only when
        there's exactly one item. Also returns `age` + `gender` → Channel3 `filters.age` / `filters.gender`
        (gender only for clear men's/women's: Channel3's `male` filter drops unisex/untagged products).
      - Crop = box + 10% padding, ≤768 px. Image search now waits for identify (it needs the box), so
        critical path = identify + max(image, text search) + detail.
      - Colour: `chooseColor` maps Gemini's colours to store names via a colour-family table (brown → Coco,
        Truffle, palomino brown; literal word wins ties), then detail is fetched with `?option_Color=<label>`.
        Offers whose chosen colour has no existing / buyable size are dropped and backfilled (one extra wave).
      - Sizes normalized for matching saved sizes: SML→S, MED→M, X-Large→XL, 2XL→XXL, "XS 2-4"→XS,
        plus sizes stay 1X/2X/3X, "US 10"→10, "7 1/4"→7.25; kids' "1½-2Y" → null. "Size Type" (Petite/Tall)
        is not the size option.
      - Category filter also keeps image-search categories that are siblings of the target ones
        (pants > joggers-4 next to pants > trousers-3), so a vague "pants" still returns joggers.
      - Emulator results (Oct 21): lamp → 1 item, auto results, 6.2 s (identify 2.5, text 2.2, detail 1.5).
        hoodie photo → 3–6 items (hoodie, cap, sweatpants; sometimes T-shirt hem, plant, desk) in 3.0–4.1 s;
        each chosen item then 1.7–5.3 s. Hoodie: brown oversized hoodies with colour + sizes (Coffee Brown,
        XS–2XL). Cap: dad/trucker caps (the cap's logo pulled Diesel look-alikes once). Pants: joggers/sweatpants
        3 of 5 after the sibling-category fix (was 1 of 5 + $1,595 wool trousers).
      - Known gaps: >6 s when Gemini or Channel3 has a slow moment; Gemini still says "pants"/"trousers" for
        sweatpants; no screenshot fixture tested yet.
- [x] Spike 2: approve PayPal once with vault, then charge again server-side with no redirect
      (`server/spikes/spike2.mjs`). Learned:
      - Requires **Save payment methods** enabled on the sandbox app, else checkout shows "Something went wrong" after login.
      - Create with `payment_source.paypal.attributes.vault = {store_in_vault: ON_SUCCESS, usage_type: MERCHANT,
        customer_type: CONSUMER}`; approval link is `rel: payer-action`; capture returns
        `payment_source.paypal.attributes.vault.{id, status: VAULTED, customer.id}` and `purchase_units[0].shipping`.
      - Repeat charge: create order with only `payment_source.paypal.vault_id` → returns `COMPLETED` on create
        (no separate capture call). Send a `PayPal-Request-Id` header.
      - Sandbox in a private window if the browser is logged into live paypal.com.
- [x] Purchase path: rules engine (`rules.ts`, 14 unit tests), `purchaseOffer` / `approvePurchase` /
      `undoPurchase`, activity log, month-to-date spend, signed `itemsToken` + `offerId`.
      `node test/purchase.mjs` seeds `users/scout-test` (rules: $300/month, $75/item, home-garden +
      apparel-accessories, ask on new store, saved places only, ask if unsure about size; Home = 2211 N First
      St, San Jose, CA 95131; sandbox vault token; known store wayfair.com) and runs (Oct 21, emulator + sandbox):
      - $41.99 Wayfair lamp → AUTO_BUY → streamed at 2.8 s, charged after the undo window → PayPal
        `9NE83181CG306990T` COMPLETED, shipped to Home.
      - $129 AllModern throw → NEEDS_APPROVAL ("$54 over your $75 limit", "First time buying from
        allmodern.com") → approvePurchase → `41H86773GG545443B` COMPLETED, shipped to Home.
      - Best Buy earbuds → BLOCKED ("Electronics isn't in your allowed categories", "Not available at
        bestbuy.com any more"), nothing charged.
      - Lamp again → Undo after 1 s → `undone`, no PayPal order, spend released. Month-to-date: $170.99.
      - Tampered offerId, another user's offerId, forged itemsToken → rejected; unsaved place → BLOCKED.
      Learned:
      - **Channel3 product detail returns ONE preferred store's offer** (search listed Best Buy $34.99 +
        Soundcore $49.99; detail only Soundcore). Purchases pin the store with `?website_ids=<domain>`; an
        empty result means the listing is gone → blocked, never a silent store/price switch.
      - PayPal vault orders accept `payment_source.paypal.experience_context.shipping_preference:
        SET_PROVIDED_ADDRESS` + `purchase_units[0].shipping`; the order shows our saved address.
        `PayPal-Request-Id` = purchase id makes charge retries idempotent.
      - The undo window runs inside the purchaseOffer request (decision streamed first). If the instance dies
        mid-window the purchase stays `pending_undo` → later: Cloud Tasks / a sweeper.
      - Charges go to our sandbox merchant, not the retailer: ordering from the store (Channel3 checkout or
        manual fulfilment) is still open.
      - The emulator reads `.env.local` at start: restart it after adding a key.
- [x] Price watching + findSimilar + demo-store labels. `node test/purchase.mjs` now also (Oct 21):
      - Watch the $41.99 Wayfair lamp at $50 → `checkWatchesNow` → "Price dropped to $41.99 (your target $50)"
        → rules → AUTO_BUY → PayPal `8PJ30567YE2454526` COMPLETED; watch → `triggered`, second check buys
        nothing. Clean run totals: orders `6PJ14576LL9206831` ($41.99), `4CP6202361664924Y` ($129),
        `8PJ30567YE2454526` ($41.99); month-to-date exactly $212.98.
      - findSimilar on the lamp: "More like this" 5 bedside lamps ($19.99–$107.88, 5.6 s);
        "Find cheaper" (max $41.99) 5 lamps $19.99–$41.99 (1.5 s).
      Learned:
      - Channel3 price tracking (v0, per API key, shared by all our users — never stopped per user):
        `POST /v0/price-tracking/start|stop {canonical_product_id}` → `{subscription_status}`;
        `GET /v0/price-tracking/history/{id}` → `{history: [{timestamp, price, currency}]}` (the guide says
        `price_history`); `GET /v0/price-tracking/subscriptions` → `{items, next_cursor}`. Price-change
        webhooks need a public endpoint set in the Channel3 dashboard; we poll product detail (pinned to the
        store) on a schedule instead, which also gives the store-specific price.
      - `/v1/similar` rejects `filters.category`; it takes `category_ids` (slugs; a parent includes children),
        plus age/gender/price like search.
- [x] Fulfilment, PayPal link, stuck-purchase cleanup (Oct 21, emulators + sandbox; `test/fulfillment.mjs`,
      `test/link.mjs start|complete`):
      - Lamp → PayPal `28S19710ME514191T` → demo order DEMO-21546 placed → confirmed → shipped (PayPal tracker
        `92625043FP593035T-DEMO110525153915609`, status SHIPPED) → delivered. Cancel after delivery refused.
      - Lamp again (`3M441463WS211122Y`) → confirmed → cancelOrder → refund `30W93440AD447982R` COMPLETED $41.99,
        capture REFUNDED, purchase `refunded`, month-to-date $83.98 → $41.99. Second cancel refused.
      - Stuck pending_undo purchase + two cleanup runs at once → one skipped, one completed: 1 charge attempt,
        1 PayPal order (`5SF357644H892950W`, reference_id = purchase id), 1 demo order. Third run finds nothing.
      - Fresh user link: setup token approved as fetchBuyer → completePayPalLink → linked, Home suggested from
        PayPal shipping (2211 N First St, San Jose, CA 95131), no vault token in the response, nothing charged.
        Replaying the approval or completing it as another user is refused.
      Learned:
      - **Vault v3 setup tokens work for PayPal wallets** (`POST /v3/vault/setup-tokens` with
        `payment_source.paypal {usage_type: MERCHANT, customer_type: CONSUMER, usage_pattern, experience_context
        {return_url, cancel_url, shipping_preference: GET_FROM_FILE, vault_instruction: ON_CREATE_PAYMENT_TOKENS}}`
        → `rel: approve` link; then `POST /v3/vault/payment-tokens {payment_source: {token: {id, type: SETUP_TOKEN}}}`
        → `id` (= vault_id) + `payment_source.paypal.{email_address, shipping.address, address}`). No charge needed.
        Custom-scheme return URLs (`fetchit://…`) are accepted.
      - On desktop the approval ends on PayPal's "sending you back" spinner because nothing opens `fetchit://`;
        the setup token is already APPROVED by then.
      - Re-linking the same PayPal account returns the **same** vault id (permit_multiple_payment_tokens: false).
      - Order and payment-token specs: github.com/paypal/paypal-rest-api-specifications (openapi/*.json).
- [x] **Backend live and tested in production** (fetchit-437a0, us-central1, Node 22, minInstances 0; see DEPLOY.md).
      Oct 23 live run (`functions/test/live.mjs`, fresh email/password user): setRules → startPayPalLink (approved as
      fetchBuyer) → completePayPalLink → confirmPlace → fetchItem(lamp) → purchaseOffer → approvePurchase → order
      reached `confirmed` → second purchase → cancelOrder (refund) → activity. Client latencies: fetchItem 17.9 s
      cold / 11.7 s warm (identify 10.5 s), purchaseOffer 5.6 s cold / 8.3 s warm (incl. 5 s undo), approvePurchase 7.0 s,
      cancelOrder 8.2 s, the rest 3.9–7.8 s on first call. Channel3: 4 credits (2 per fetchItem; detail is free).
      Scheduled functions run (advanceOrders every minute, sweepStuckPurchases 5 min, checkWatches hourly).
      Learned:
      - First deploy: one function hit `409 Could not create bucket gcf-v2-sources-…` (retried fine) and the
        Artifact Registry cleanup policy had to be set separately.
      - `setGlobalOptions` in index.ts ran after the other modules' functions were defined (ESM imports run first):
        moved to `options.ts`, imported first.
      - startPayPalLink → INTERNAL in prod was PayPal `401 invalid_client`: the client ID had been stored as
        PAYPAL_CLIENT_SECRET. Fixed (v2), functions redeployed (they pin secret versions). PayPal errors now log and
        return PayPal's error name, issue and debug_id.
- [x] Ranking: relevance = position-weighted, image 1.0 × text 0.6 (both → higher); × price-sanity factor
      (1 within ½–2× the median price of the visual matches, shrinking to 0.5 at 4×/¼×, floor 0.35 — outliers sink,
      never dropped). 7 hydrated, best 5 returned, each with `score {final, relevance, imageRank, textRank,
      priceFactor, medianPrice}`. Items carry `prominence`; clutter < 0.3 dropped; one clear subject (≥ 0.75 with the
      runner-up ≤ 0.45) continues without "Which one?".
- [x] Identify race (Oct 23). Benchmark (`functions/spikes/identify-bench.mjs`, lamp + flat-lay knit polo, 5 runs,
      crops in `fixtures/out/crops/`):
      | Model | Median | Worst | Errors | Items/run | Box vs Gemini | Channel3 top-5 vs Gemini |
      |---|---|---|---|---|---|---|
      | Gemini 3.5 Flash-Lite | 2.0–2.6 s | **10.9 s** | 0 | lamp 1–4 (unstable) | — | — |
      | Gemini 3 Flash preview | 2.1–2.7 s | 5.3 s | **15 s timeout, 3× quota 429** | 4 | IoU 0.96 | 4–5/5 |
      | Claude Haiku 5.5 | 2.1–3.1 s | 4.3 s | 0 | always the same | IoU 0.75–0.84: clips the lamp base, polo width varies 0.73–0.97 | 2–4/5, same #1 |
      → `identify` (src/lib/identify.ts + race.ts): Flash-Lite and Claude start at t=0; Gemini accepted the moment it
      answers, Claude only after a 3 s grace or once Gemini failed; if Claude fails (or no key), Gemini 3.6 Flash takes
      the backup slot. 3 Flash preview dropped. Low confidence (< 0.6) → one 3.6 Flash re-ask. `npm test` covers the
      race timing (9 tests; found and fixed an early-firing grace timer that would have stalled Claude to 15 s).
      Same day Flash-Lite was in a 503 "high demand" outage (image calls hung 15 s): with the race every identify
      finished at ~3.0 s (Claude); bad Claude key → 3.6 Flash at 3.0 s; no Claude key → 5.6 s; both keys bad → error in 0.7 s.
- [x] Race deployed (Oct 25, fetchItem rev 00003; ANTHROPIC_API_KEY v1 verified: matches .env.local, API 200).
      `test/measure.mjs` on lamp in prod, 4 warm calls: **identify 1.3–1.7 s (median 1.64 s)**, **fetchItem server
      2.2–3.0 s (median 2.78 s)**, client 2.9–3.9 s; cold start 9.1 s client / 4.0 s server. Flash-Lite won all 5
      races (Claude cancelled each time). Image search 0.7–1.3 s, text 0.6–1.1 s, detail 0.15–0.2 s in prod.
      Was: identify 10.5 s / fetchItem 11.7 s warm on Oct 23.
      - Secret-setting lesson (twice): `secrets:set` "succeeded" in another project. Always check the output says
        `projects/789368648104/…` (= fetchit-437a0), or verify with `functions:secrets:get … --project fetchit-437a0`.
- [x] Ranking anchor (Oct 25, deployed fetchItem rev 00004, findSimilar rev 00003): price-sanity now compares
      against the **median of the cheaper half of image + text matches** (`PRICE_ANCHOR = "cheaper_half"`). Same-pool
      comparison: image median ($885 lamp / $75 polo) and image+text median ($675 / $75) let pricey vintage/designer
      look-alikes set the bar (the $99 lamp vanished; the polo's best visual match fell to #3); cheaper half ($99–108 /
      $33–42) keeps the polo's best match #1 and puts the affordable lamp first or second. Trade-off: the closest
      visual lamp match ($367) can drop to #2 behind a $99 look-alike; widening the no-penalty band to ¼–4× would
      keep it #1. 6 ranking unit tests (29 total).
- [x] Channel3 US + English (Oct 28). The OpenAPI spec (`api.trychannel3.com/openapi.json`) has `config: {country,
      language, currency}` on search/image-search/similar and `?country=&language=&currency=` on product detail
      ("matches any language when unset"). Every call now sends US/en/USD (`LOCALE` in lib/channel3.ts). Measured:
      search results were identical with and without it (search already defaults to USD/en), the Norwegian title had
      come from detail and is English today, and one US Zara listing is titled in Spanish even with US/en ("Polo
      Punto Jacquard Rayas"). So also `looksNonEnglish(title)` (foreign letters like ø/ñ, or non-English shop words
      such as rayas/punto/med) → `languageFactor` 0.3 in the final score (findSimilar: sorted last). 1 unit test
      (35 total). Emulator vs prod: outfit → 5 English titles; abercrombie.com buy → live check "In stock" → declined.
- [x] App Check (Oct 28): wired, **off through judging** (decided Oct 29: judges sideload the APK, which can't get
      Play Integrity tokens; credits are protected by rate limits + circuit breaker instead). `enforceAppCheck: ENFORCE_APP_CHECK` on all 16 callables
      (param, false in .env.fetchit-437a0); app sends `X-Firebase-AppCheck` on callables + Firestore (debug provider in
      debug builds, Play Integrity in release, by source set); fetchItem / registerPushToken log `appCheck`.
      Verified: prod fetch from the debug build works and logs `appCheck: missing` (debug token not allow-listed, App
      Check API off); forged token sent to prod → "AppCheck token was rejected" (allowed only because not enforced);
      emulators with ENFORCE_APP_CHECK=true → no token = 401 (the emulator skips signature checks, so forged passes
      there). To see `VALID` verdicts from your own debug build, allow-list its debug token (DEPLOY.md → App Check).
      The app skips App Check for 5 min after a failed token fetch, so a sideloaded release isn't slowed by the 4 s
      timeout on every call.
- [ ] PayPal webhooks
- [x] App, Android (Oct 26, Pixel_4 emulator against production): onboarding → fetch → results → buy/approve → activity.
      Firebase over plain REST from shared Kotlin (`shared/.../data/Firebase.kt`): **anonymous** sign-in (provider enabled
      in fetchit-437a0; PayPal is the real identity), callable functions incl. SSE streaming (fetchItem `items` chunk →
      "I think it's a…"; purchaseOffer decision → undo countdown), Firestore REST reads of users/{uid}/…. The API key is
      generated at build time from `androidApp/google-services.json` (gitignored, as is `iosApp/iosApp/GoogleService-Info.plist`).
      Run on emulator: Welcome → PayPal link (Custom Tab, fetchit://paypal/return deep link) → Home suggested + confirmed →
      rules ($200/$75) → sizes (local only, no setProfile yet) → name → lamp photo → 5 offers ($99–$2,270, all
      "over limit, I'd ask you") → $367 BLOCKED (budget) → $99 NEEDS_APPROVAL → approve → PayPal `7EN777939A391250W`,
      "Arrives Thursday" from the order → outfit → $22.90 polo AUTO_BUY (new-store ask off) → `97A010692L788711P` →
      again + Undo → `undone`, no PayPal order, spend 121.90. Activity showed the lamp Shipped with DEMO tracking.
      Learned:
      - Emulator Chrome crashed on PayPal's page (`Timed out waiting for GPU channel`) and the tab closed: run the AVD
        with `-gpu swiftshader_indirect -memory 4096`. Software rendering is slow (1–2 s frames) and once showed a stale
        frame after the photo picker: Fetch drawn while Results was live, so "+" sat over Buy. Buy now ignores taps for
        the first 0.8 s on Results; still, one unexplained BLOCKED buy happened during that stale-frame period.
      - With the lag, a scripted Undo read via uiautomator (~2–3 s per dump) missed the 5 s window; tapping straight away worked.
      - iOS actuals are stubs (photo picker, LocalAuthentication, ASWebAuthenticationSession); iOS not compiled yet.
      - No lock screen on the device → the "Approve with fingerprint" tap itself counts (BiometricManager can't authenticate).
- [x] Stray-Buy fix (Oct 28). Two unintended buys on Oct 26: $367 BLOCKED (10:07:49Z) and $22.90 **charged**
      (10:27:47Z, `5FT94904E0674090T`). Server logs: each purchaseOffer arrived as a tap meant for something else
      landed on Buy — results had pushed the Results screen on top of another screen (Buy sits where the nav bar and
      "+" are) while the slow emulator still drew the old frame. The second was preceded by two fetchItem requests
      100 ms apart for one photo: OkHttp's silent POST retry (`retryOnConnectionFailure`, on by default).
      Fixes: `purchaseOffer` requires a `requestId` (doc id; pre-check + re-check in the decision transaction; replays
      of an in-flight purchase wait for the outcome; reuse for another offer → invalid-argument). App: one requestId
      per tap, the call runs in AppState keyed by it (one tap = one call), OkHttp retries off and replaced by an
      explicit retry only for requests that got no response, Results/Which one? only open if the user is still on
      the screen that asked (else a "I found something!" card on Fetch), Buy arms 0.8 s after its first draw and only
      a press that *starts* armed counts. `npm run test:idempotency` (emulators + sandbox): same Buy ×3 (2 concurrent +
      1 after) → 1 purchase, 1 PayPal order, 1 spend reservation, all three responses `completed`; reused id and
      missing id rejected. Emulator vs prod: switching to Rules mid-fetch + 6 taps where Buy would be → 0 purchase
      calls; instant tap on a fresh Results ignored, later tap → exactly 1 call.
      - The emulators need Java 21+: `JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-21.0.5/Contents/Home`.
- [x] setProfile (Oct 28): sizes/fit/dog name/ask-if-unsure server-side; the rules engine's size rule falls back to the
      saved size ("Your size MED is in stock", recorded on the purchase as the store's label; sold out → BLOCKED);
      the profile's askIfUnsure wins over the rules field. App saves through setProfile, keeps a local cache, and the
      server copy replaces the cache on refresh. Tests: 5 new rule unit tests (34 total), `npm run test:profile`
      (emulators + live Channel3, no charge: Nordstrom fleece, no size sent + top S → "Your size Small is in stock").
      Emulator vs prod: sizes L/32/US 9 saved from Account → profile/main; rename to Biscuit → server; cached name
      set to Rex on the phone → back to Biscuit after the start-up refresh.
      - A brand-new callable answered 401 ("not authorized to invoke this service") for ~2 min after its first
        deploy: the allUsers invoker binding lags behind creation. Wait, or redeploy, before testing a new function.
- [x] Push (Oct 28): FCM on Android via the native SDK only for messaging (FirebaseApp initialized in
      `FetchApplication` from the generated config: no google-services plugin). The app registers its token after
      onboarding/start (`registerPushToken`), asks for POST_NOTIFICATIONS once on the Fetch screen. `push.ts notify()`
      (best effort, never fails a purchase): NEEDS_APPROVAL → "Needs your OK: <title> · $X" (watch: "Price dropped: …"),
      tap opens the approval sheet; other watch outcomes → "Price dropped: …" with the result, tap opens the purchase.
      Channel `approvals`, dog-face status icon; a notification for the purchase whose sheet is open isn't shown.
      Emulator vs prod (watch docs `pushtest`, `pushtest2` written for the test user, checkWatches force-run from Cloud
      Scheduler): app in background → notification → tap → approval sheet; app killed → tap cold-starts into the
      sheet; in-app Buy needing approval → push sent (log `push sent`) but not shown over the sheet. All declined.
- [x] Cancel order (Oct 28): Activity cards for paid purchases → "Order · cancel" / "Order details" → Order screen
      (item, total, store + DEMO order id, refund note, timeline = every Activity entry for the purchase, refreshed
      every 5 s). Before shipping: "Cancel order" → "Tap again: cancel and refund $X" → `cancelOrder`. A failed
      cancel's activity entry now carries the purchaseId. Emulator vs prod: $22.90 polo auto-bought → cancelled
      within the 2-min window → order DEMO-98303 `cancelled`, PayPal refund `4M858077FS514363F` COMPLETED, spend back
      to $144.80, card "Cancelled. $22.90 refunded to your PayPal."; a shipped order shows no Cancel.
- [x] Credit protection (Oct 29, deployed; DEPLOY.md → Credit protection). Per-user rate limits in `limits/{uid}`:
      fetchItem 30/h + 200/day, findSimilar 30/h, purchaseOffer 20/h → `resource-exhausted` "<dog> needs a quick nap,
      try again in a few minutes." (hour) / "…a long nap, try again tomorrow." (day); refused calls aren't counted;
      fails open on Firestore errors. Global daily circuit breaker: `meter.record()` in lib/gemini, lib/claude,
      lib/channel3 (search, image search, similar; detail is free) → `usage/{day}`; caps `DAILY_CAP_GEMINI` 3000,
      `DAILY_CAP_CLAUDE` 1500, `DAILY_CAP_CHANNEL3` 1000 (params in .env.fetchit-437a0); instances re-read every 15 s.
      Over cap → ERROR log `circuit breaker tripped` (once per instance/day); Gemini/Channel3 → fetches refused with
      the long-nap message, Claude → identify runs on Gemini alone. `npm run test:limits` (emulators, 1 real fetch, 5 tests).
      Spending caps on the keys themselves: DEPLOY.md (Cloud console API quotas for Gemini, Anthropic Console limits).
- [x] Release signing (Oct 29): keystore `~/.fetchit/fetchit-release.jks` (outside the repo, alias `fetchit`, back it
      up: DEPLOY.md → Release signing), read from `fetchit.release.*` in `app/local.properties` (gitignored); without
      them the release build is unsigned. Signed release APK on Pixel_4 vs prod: fresh onboarding (PayPal link as
      fetchBuyer, Home suggested, $200/$75 rules, sizes, Scout) → outfit photo → $32.90 zara.com polo NEEDS_APPROVAL
      (first time at zara.com) → approved → PayPal `93P174878P978663V`, DEMO-38408 confirmed, arrives Sunday, spend
      $32.90; limits/usage counted; App Check `MISSING` on every call (expected, not enforced). Fixed: the Fetched
      screen showed the budget from before the purchase (now refreshes month-to-date on open).
- [~] Delivery places (Nov 5, deployed addPlace / deletePlace / setDefaultPlace, confirmPlace updated). Onboarding step 2
      "Where should I deliver?" (5 steps now): PayPal's address as a paper note "from PayPal" → "Yes, deliver here"
      (confirmPlace, pin drops) or "Use a different address" → address form (label Home / Work / Other, name, street,
      apt, city, state, ZIP, optional phone; client checks mirror `lib/address.ts`, server field errors shown under each
      field); no PayPal address → the form. You → Saved places: pinned default, make default, edit, delete (deleting the
      default asks which place takes over; the only place can't be deleted). Results: "ship to ▾" opens a sheet to pick
      the place for this purchase (Buy re-arms 0.8 s after the sheet closes); `Screen.Buying` carries the placeId.
      Rules engine unchanged (suggested places still don't count). Tests: `npm test` (+5 address unit tests, 40 total),
      `npm run test:places` (emulators, 6 checks: suggestion replaced, field errors, duplicate labels, edit, default
      switch, delete rules + watches move). **Not yet run on the Android emulator** (PayPal-suggested + manual paths).
- [x] Navigation + doodle icons (Nov 5): Activity · Fetch · You tab bar with the raised ear-topped Fetch button,
      sliding pill, icon pop, approval dot; "+" and the Fetch header's profile circle removed; You tab; Rules pushed
      with focus; doodle icons for tabs, search (sniffing), check (prices checked, paid activity), star + arrow (my
      pick), map-pin (ship to, places), pencil (edit), heart (fetched), bell / gear / lock / help / log-out / ruler / dog /
      shield-check (You). Size picks keep the hand-drawn pen loop (`wobblyLoop` drawn on), not the doodle circle icon.
      Mic has no screen yet (voice isn't built). Builds; not yet seen on a device.
- [~] Category override (Nov 5): a category outside the rules now asks (fingerprint) instead of blocking; Rules lists all
      16 Channel3 roots incl. Health & Beauty; approval sheet "Always allow <category>". `npm test` 41 (category tests
      updated), `npm run test:category` (emulators + live Channel3 + sandbox): perfume → NEEDS_APPROVAL with only
      the category failing → approve + allowCategory → sandbox order `0BU61090EM899792U`, rules gain health-beauty,
      activity logged. Deployed Nov 5 (purchaseOffer, approvePurchase, checkWatches, checkWatchesNow,
      sweepStuckPurchases, runMaintenance). The new Rules chips and approval switch need the new app build; not yet
      seen on a device.
- [~] Design pass (Nov 7), committed per group, not yet seen on a device:
      1. Scout presence: `ScoutFace` (head only; pupils shift via `DogPose.look`, random glances, blinks every 2.4–4.6 s;
         `lookAt` overrides) and `ScoutPeek` (top part rising over an edge). Welcome: big face peeking from the bottom,
         looks up at "Get started" after 2.6 s. Every onboarding step: a small peek under the progress bar. Ready →
         Fetch: big Scout shrinks into the peek over the viewfinder (`Modifier.sharedShape(SCOUT_KEY)`, shared bounds
         with scale-to-bounds; the screens cross-fade 450 ms). Results: a peek over the Buy bar whose eyes follow the
         last tap (pointer events watched at `PointerEventPass.Initial`, never consumed).
      2. Welcome headline on two tilted paper-tape strips ("You spot it." / "We fetch it.") that slap on 300 ms apart
         (`TapeStrip`: 1.3→1 spring, light tap); a pen arrow draws from the lamp polaroid to "spotted at a café".
      3. Onboarding selections: budget and auto-buy limit on side-by-side 3D drums (`DrumPicker`: snap fling, tick
         haptic per value, off-centre rows tip/shrink/fade/blur; "type it" swaps in AmountField and the typed value joins
         the drum) with a mood caption ("$200 a month · Sensible shopper"); categories as full-width rows that fill black
         from the left with a drawn ✓, a live counter ("3 categories" / "none selected"); disabled dark pills are dim
         gray and light up to black when the choice becomes valid.
      4. Loading: `Modifier.blurIn(index)` (blur 14→0 dp, fade up, slight rise, 60 ms stagger; fade under reduce motion;
         below Android 12 blur isn't drawn) on Results cards and Activity cards; Activity shows blurred placeholder notes
         until its first load (`AppState.activityLoaded`).
      5. Order route (`OrderRoute` in OrderScreen): dotted cubic path from the store (cart doodle) to the ship-to place
         (home doodle) with checkpoints; a mini polaroid of the item rides it (placed 0, confirmed 0.22, shipped 0.62,
         delivered 1; 600 ms on open and on each status change); cancelled stays put with a red ✗.
      6. Results: a heart on every card (`HeartButton`: pop 1.35→1 + pen-stroke burst + tick; red and bolder when saved)
         → `saveItem`; You → "Saved for later" (`SavedScreen`: notes build in, tap → buy panel at the live price via
         `savedOffer`, sizes, ship-to sheet, Buy arms after 0.8 s). "More matches" under the grid loads `findSimilar` for
         the top result into a swipeable fan of tilted polaroids (`PolaroidFan`: drag, spring-snap to the nearest card,
         tap the front one to pick it for Buy). `npm run test:saved` (emulators + Channel3 detail, 3 tests). Deployed Nov 7.
      7. Activity: watch buys carry `priceWas` (the watch's startPrice) on the purchase and every activity entry; the card
         heading strikes the old price through with a pen line and the new price rises in, counting down from it
         (`PriceDrop`). `node --test test/watchprice.mjs` (emulators, no charge: $59.99 → $42.99, needs_approval,
         priceWas on purchase + activity). Deployed Nov 7.
      1b. (Nov 7, after feedback: the cropped mascot face under the button looked bad.) Redrawn after the user's reference
         clips: `ScoutHero` (motion/ScoutHero.kt) is Scout simplified to a bold domed head on a 400×260 box: white dome with
         ink outline, black floppy ears hanging off the screen edges, big outlined eyes with pupils + highlight (patch
         behind the right one), nose, smile, red tongue. Welcome: it fills the bottom edge to edge, rises in, eyes glance
         and blink, then look down at "Get started", which sits on his head; ears sway slightly. `ScoutPeek` now draws the
         same head showing only dome + ears + eyes (onboarding steps, viewfinder, Results). `ScoutFace` (old style) is only
         in the Motion lab now.
      1c. One face everywhere (Nov 7): the full-body dog's `drawHead` (Mascot, every DogMood, RunningDog, Ready) now has the
         ScoutHero face scaled into the head (big outlined eyes with pupils + highlight, patch behind the right eye,
         nose, smile, tongue; closed-eye arcs for blink/sleep); the app icon (adaptive foreground + monochrome, PNG
         mipmaps, iOS 1024) uses the same face. The notification icon is unchanged (a one-colour silhouette).
      Nov 7 follow-ups: one Scout per onboarding screen (`StepHeader(peek = false)` on Link PayPal, which shows the whole
      Scout at 92 dp wagging, and on Meet your fetcher); Wag is livelier (±15°, 0.5 s); Which one? circles each item
      with a drawn-on pen loop (`wobblyLoop` sized ~1.28 × 1.24 the box + 10 dp, kept inside the photo) instead of
      dashed boxes.
      Which one? (Nov 7): nothing picked to start; tapping an item ticks it (black fill, drawn ✓, its loop turns red)
      and fetches it right away (the multi-select + "Fetch these" button was tried and dropped: one item came back and
      the second was easy to miss). `AppState.chooseItems` still takes several indices (one fetchItem each, in
      parallel, reusing the signed itemsToken). Results opens on the first item that found
      something, with a tab per searched item (`FetchSession.results` / `current`; items with nothing show "none
      found"); each tab's view is keyed so its sort, pick and "More matches" start fresh. The "choose" response is kept
      in `FetchSession.response` (no longer overwritten).
- [~] Snaps (Nov 7): every fetched photo is kept **on the phone only** (`PhotoFiles` expect/actual: Android
      filesDir/snaps/<id>.jpg; iOS Application Support, untested) with an index in KeyValueStore "snaps" (`Snap`: label =
      first identified item, last search's offers as product|store → price, checkedAt; newest 60 kept; all deleted on
      sign-out). Fetch has a snaps chip (newest stamp + count) beside the budget chip; You → Your snaps is the album (`SnapsScreen`: postage stamps with
      perforated edges on graph paper, 3 columns, blur-in; Select → check dots → red "Delete N", tap twice). A snap's
      sheet → **Search again** (`AppState.searchAgain`: same photo through the normal fetch); Results then says
      "2 new · 1 cheaper since Nov 5" (or "Nothing new"), tapes "new!" on new matches and strikes through the old price
      on cheaper ones. No server storage and no automatic re-checks (decided Nov 7). Privacy text in You updated.
      Builds; not yet seen on a device.
- [~] Price watches in the app (Nov 7): Results has a "watch price" pill left of Scout's peek (black "watching ≤ $X" once
      watched) → `WatchSheet` (ui/Watch.kt): target as an AmountField (default ~10% under, chips 10/20/30% off; at or
      over today's price is refused, over the per-item limit warns it'll still ask), uses the picked size + ship-to
      place; Change target / Stop watching when already watched. Buy disarms while the sheet is open. You → Price
      watches (`WatchesScreen`): live watches with target, last price, stop; recent "price dropped" ones below.
      `npm run test:watch` (emulators + Channel3 detail, 5 tests, nothing charged). watchOffer + stopWatch deployed
      Nov 7 (both answer); not seen on a device.
- [~] Fetch header (Nov 7, after a scrapbook-app reference): "What should I fetch?" with two bare doodle icon buttons on
      the right (red heart → Saved for later, filled once anything's saved; price tags → Price watches), each with a small
      count badge (watches: green when a watched price is below its start price, red when no drop yet); today's date under it ("7th" serif + "November"); then a pill row: "$X left this month"
      as the black pill (→ Rules) and the gray snaps pill, both 44 dp. Builds; not seen on a device.
      Nov 7 follow-up: `Doodle.PriceTag` (not in the set: two stacked tags + "$", built on the set's tag.svg, after the
      user's reference) replaces the bell for watches (header, watch pill, You row). Red/green dot on the watch pill and
      on each live watch in Price watches (`Watch.dropped` = lastPrice < startPrice). Saved for later: the buy panel has
      the watch pill too (same `WatchSheet`, the saved item's live offer + picked size).
