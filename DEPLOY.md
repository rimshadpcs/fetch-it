# Deploying the Fetch It backend

Firebase project **`fetchit-437a0`** (display name "fetchit"), Blaze plan. Region **us-central1**, Node 22,
Cloud Functions 2nd gen. Use the current CLI through npx (the global 13.x CLI predates Node 22 functions):

```bash
alias fb="npx -y firebase-tools@15.34.0"
```

## What lives where

| | Where | In git? |
|---|---|---|
| Secrets: `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `GEMINI_API_KEY`, `ANTHROPIC_API_KEY`, `CHANNEL3_API_KEY`, `FETCH_SIGNING_KEY` | Cloud Secret Manager (`defineSecret`) · locally `functions/.env.local` for the emulator | **Never** |
| Non-secret config: `PAYPAL_BASE_URL`, `FULFILLMENT_*` timings, `ALLOW_DEBUG=false`, `DEMO_MIN_INSTANCES` | `functions/.env.fetchit-437a0` (deployed with the functions) | Yes |

Never put a secret in `functions/.env` or `functions/.env.fetchit-437a0`. Production `FETCH_SIGNING_KEY` is its own
random key, not the local one.

## Deploy

```bash
cd functions && npm test && npm run test:rules   # type-check + build + 14 rule-engine tests; rules test needs the emulators
cd .. && fb deploy --only functions,firestore:rules,firestore:indexes --project fetchit-437a0
```

- `predeploy` builds TypeScript to `functions/lib/`. The bundle ignores `src`, `test`, `spikes`, `fixtures`, `*.local`.
- Deploy only some functions: `fb deploy --only functions:fetchItem,functions:findSimilar --project fetchit-437a0`.
- First deploy on a fresh project can fail one function with `409 Could not create bucket gcf-v2-sources-…`
  (functions racing to create the source bucket). Rerun; it succeeds.
- If deploy ends with "could not set up cleanup policy": `fb functions:artifacts:setpolicy --location us-central1 --project fetchit-437a0 --force`
  (already set: images older than 1 day are deleted).
- Check: `curl https://us-central1-fetchit-437a0.cloudfunctions.net/health` → `{"ok":true,…}`. Debug callables
  (`advanceOrder`, `runMaintenance`) must answer `Debug functions are disabled.`

## Rotate a secret

Deployed functions are pinned to the secret **version** that existed at deploy time, so a new version only takes
effect after redeploying the functions that use it.

1. Add a new version (prompted value is hidden; or pipe it so it never shows on screen):
   ```bash
   fb functions:secrets:set PAYPAL_CLIENT_SECRET --project fetchit-437a0
   ```
   ```bash
   grep '^PAYPAL_CLIENT_SECRET=' functions/.env.local | cut -d= -f2- | tr -d '\n' | npx -y firebase-tools@15.34.0 functions:secrets:set PAYPAL_CLIENT_SECRET --project fetchit-437a0 --data-file -
   ```
   For the signing key, generate a fresh one: `openssl rand -hex 32 | tr -d '\n' | … functions:secrets:set FETCH_SIGNING_KEY … --data-file -`
   (rotating it invalidates outstanding `offerId`/`itemsToken`s, which just means "fetch again").
2. Redeploy the functions bound to it. The CLI prints the list after `secrets:set`; or redeploy all functions.
   | Secret | Functions |
   |---|---|
   | `PAYPAL_CLIENT_ID` / `PAYPAL_CLIENT_SECRET` | startPayPalLink, completePayPalLink, purchaseOffer, approvePurchase, cancelOrder, watchOffer, checkWatches, checkWatchesNow, advanceOrders, advanceOrder, sweepStuckPurchases, runMaintenance |
   | `CHANNEL3_API_KEY` | fetchItem, findSimilar, purchaseOffer, approvePurchase, watchOffer, checkWatches, checkWatchesNow, sweepStuckPurchases, runMaintenance |
   | `GEMINI_API_KEY` | fetchItem |
   | `ANTHROPIC_API_KEY` | fetchItem (Claude Haiku 5.5 races Gemini in identify; must exist before fetchItem deploys) |
   | `FETCH_SIGNING_KEY` | fetchItem, findSimilar, purchaseOffer, approvePurchase, watchOffer, checkWatches, checkWatchesNow, sweepStuckPurchases, runMaintenance |
3. Check the binding: `fb functions:secrets:get PAYPAL_CLIENT_SECRET --project fetchit-437a0` (metadata only) and that the
   function now references the new version (Cloud Console → Cloud Run → service → Variables & Secrets).
4. Retire the old version once nothing uses it: `fb functions:secrets:prune --project fetchit-437a0` **destroys** versions
   no deployed function references; to keep a way back, disable it in Secret Manager instead.

**Lesson from Oct 23:** a mixed-up paste (client ID stored as the client secret) only shows up as PayPal
`401 invalid_client` at runtime. After setting PayPal secrets, run the live test (`node functions/test/live.mjs`) or at
least one call to `startPayPalLink`. Errors now name PayPal's error and `debug_id` instead of INTERNAL.

## Warm instances for demo recording

Everything runs with `minInstances: 0` (no idle cost; first call after idle is a cold start, ~5–8 s extra).
`fetchItem` and `purchaseOffer` read `DEMO_MIN_INSTANCES` so they can be kept warm while filming:

1. In `functions/.env.fetchit-437a0` set `DEMO_MIN_INSTANCES=1`, then
   ```bash
   fb deploy --only functions:fetchItem,functions:purchaseOffer --project fetchit-437a0
   ```
2. Record. (A warm instance is billed while idle, `fetchItem` at 1 GiB.)
3. **Turn it off afterwards:** set `DEMO_MIN_INSTANCES=0` and run the same deploy again.

## App Check

Every callable has `enforceAppCheck: ENFORCE_APP_CHECK` (param in `functions/.env.fetchit-437a0`, default `false`).
The Android app sends an App Check token (`X-Firebase-AppCheck`) on every callable and Firestore request:
Play Integrity in release builds, the debug provider in debug builds (`androidApp/src/{release,debug}`).
After a failed token fetch the app stops asking for 5 minutes, so a build without tokens isn't slowed down.

**Decision (Oct 29): `ENFORCE_APP_CHECK=false` through judging.** Judges sideload the APK, and Play Integrity only
vouches for apps Google Play recognises (installed from Play, or listed in Play Console with the Cloud project
linked), so a sideloaded release APK gets no token. Credits are protected by rate limits and a daily circuit
breaker instead (see **Credit protection**).

| ENFORCE_APP_CHECK | No token | Invalid token | Valid token |
|---|---|---|---|
| `false` (now) | allowed | allowed, logged as a warning | allowed, `request.app` set |
| `true` | 401 `unauthenticated` | 401 | allowed |

Production verifies tokens with the Admin SDK (a forged token is logged "AppCheck token was rejected"). The
functions **emulator** skips verification (`skipTokenVerification`): it only tells missing from present.

### Allow-list your debug build (to see `appCheck: "valid"` in the logs)
1. Enable the **Firebase App Check API** in project fetchit-437a0:
   https://console.cloud.google.com/apis/library/firebaseappcheck.googleapis.com?project=fetchit-437a0
2. Install and open the **debug** build (`./gradlew :androidApp:installDebug`). It shares its application id with the
   release build but not its signature, so uninstall the other one first (`adb uninstall com.rimapps.fetchit`).
3. Read the debug token the SDK generated for this install (it's a secret: paste it, don't share it):
   ```bash
   adb logcat -d | grep DebugAppCheckProvider
   ```
   The line ends "…allow list in the Firebase Console for your project: <token>". A reinstall or "Clear data" makes a
   new token, which has to be added again.
4. Firebase console → **App Check** → **Apps** → `com.rimapps.fetchit` → ⋮ → **Manage debug tokens** → **Add debug
   token**: name it (e.g. "Pixel_4 emulator") and paste the token. (If the app isn't listed as registered yet, register
   it with Play Integrity first: step 1 of the rollout below.)
5. Fetch something in the app, then check the verdict:
   ```bash
   fb functions:log --only fetchItem --project fetchit-437a0 | grep appCheck
   ```
   `"appCheck":"valid"` = token accepted; `"missing"` = no token (API off, token not allow-listed, or the 5-minute
   back-off after a failure: force-stop and reopen the app).

### Turning enforcement on later (after judging, or for a Play install)
1. Enable the App Check API (above) and the **Play Integrity API**
   (https://console.cloud.google.com/apis/library/playintegrity.googleapis.com?project=fetchit-437a0).
2. App Check → Apps → `com.rimapps.fetchit` → **Play Integrity** → register the SHA-256 of the key that signs the APK
   people install: the release keystore's (`F1:58:04:86:…:B7:A8`, see **Release signing**), or with Play App Signing
   the "App signing key certificate" from Play Console → Test and release → App integrity.
3. Allow-list debug tokens as above; check the logs show `appCheck: "valid"` from both builds.
4. Set `ENFORCE_APP_CHECK=true` in `functions/.env.fetchit-437a0` and `fb deploy --only functions --project fetchit-437a0`.

## Credit protection (rate limits + daily circuit breaker)

`functions/src/limits.ts`; emulator test `npm run test:limits`.

**Per-user rate limits** (fixed windows, UTC, counted in top-level `limits/{uid}`, which clients can't read):

| Callable | Per hour | Per day |
|---|---|---|
| `fetchItem` (a "Which one?" pick is a second call) | 30 | 200 |
| `findSimilar` | 30 | — |
| `purchaseOffer` (counted before the idempotent replay check) | 20 | — |

Over the limit → `resource-exhausted`, "Scout needs a quick nap, try again in a few minutes." (the user's dog name;
"…a long nap, try again tomorrow." for the daily limit), logged as `rate limited`. The app shows the message as is.
Limits are constants in `RATE_LIMITS`; change and redeploy. To unblock a user now: delete `limits/{uid}`.

**Daily circuit breaker** (all users, UTC day, counted in `usage/{YYYY-MM-DD}`): every Gemini call, Claude call and
Channel3 search / image search / similar (product detail is free and not counted). Caps are params in
`functions/.env.fetchit-437a0`:

| Param | Default | Over the cap |
|---|---|---|
| `DAILY_CAP_GEMINI` | 3000 | new photos refused ("long nap") |
| `DAILY_CAP_CLAUDE` | 1500 | identify runs Gemini alone |
| `DAILY_CAP_CHANNEL3` | 1000 | fetchItem and findSimilar refused |

A lamp fetch is ~1–2 model calls + 2 Channel3 credits. The first trip per instance and day logs `circuit breaker
tripped` (ERROR, with service/count/cap) and sets `usage/{day}.tripped.<service>`. Find it with
`fb functions:log --project fetchit-437a0 | grep "circuit breaker"`, or a log-based alert on that text. Each instance
re-reads the counts every 15 s, so a burst can overshoot a cap slightly. Raise a cap: edit the param, redeploy
`fetchItem,findSimilar`. Purchases, approvals and watches don't use these APIs and keep working.

### Spending limits on the keys themselves (do these too: the breaker only counts, the providers enforce)
- **Gemini** (key from Google AI Studio, billed to its Google Cloud project):
  - Hard cap: Cloud console → APIs & Services → **Generative Language API** → **Quotas & System Limits** → lower
    "requests per day" (and per minute) for the models we use (`gemini-3.5-flash-lite`, `gemini-3.6-flash`). Requests
    over a quota fail with 429, which identify already falls back from.
  - Alerts: Cloud console → **Billing** → **Budgets & alerts** → budget on that project (e.g. $20/month, alerts at
    50/90/100%). Budgets alert, they don't stop spending.
  - AI Studio → **Usage & Billing** shows spend per project; if it offers a spend cap for the project, set it there too.
- **Anthropic** (Claude Haiku key): Claude Console → **Settings → Limits** sets the organization's monthly spend
  limit. Tighter: put the key in its own workspace (Settings → **Workspaces** → create "fetch-it" → create the key
  there) and set that workspace's **spend limit** and rate limits. Over the limit the API returns errors and identify
  falls back to Gemini.
- **Channel3:** credits are prepaid in the Channel3 dashboard; the daily breaker above is the per-day guard.

## Release signing

- Keystore: `~/.fetchit/fetchit-release.jks` (outside the repo, mode 600), PKCS12, alias `fetchit`, RSA 4096,
  valid until 2054. Created Oct 29 with a random password (same for store and key).
- Certificate fingerprints (public, safe to share):
  - SHA-256 `F1:58:04:86:32:86:B0:FA:F5:A6:C1:D5:3A:DE:CD:F3:28:3E:46:6E:27:43:62:E9:F5:85:EC:7C:8B:29:B7:A8`
  - SHA-1 `1F:A9:74:6D:BE:84:E8:FD:73:9F:63:3F:7D:2D:D5:69:3E:15:24:CA`
- Gradle reads it from `app/local.properties` (gitignored); without these entries release builds are unsigned:
  ```properties
  fetchit.release.storeFile=/Users/<you>/.fetchit/fetchit-release.jks
  fetchit.release.storePassword=…
  fetchit.release.keyAlias=fetchit
  fetchit.release.keyPassword=…
  ```
- **Back it up now** (lose it and installed copies can't be updated with the same signature):
  1. Password manager: a secure note "Fetch It release keystore" with the password (from `local.properties`) and the
     `.jks` file attached.
  2. A second, offline copy: e.g. an encrypted disk image or USB drive with the `.jks` + a text file with the password.
  3. Never in the repo, Google Drive in plain form, chat, or email. Teammates who build releases get both through the
     password manager.
- Build, check the signature, install:
  ```bash
  cd app && ./gradlew :androidApp:assembleRelease
  ```
  ```bash
  apksigner verify --print-certs app/androidApp/build/outputs/apk/release/androidApp-release.apk
  ```
  ```bash
  adb uninstall com.rimapps.fetchit; adb install app/androidApp/build/outputs/apk/release/androidApp-release.apk
  ```
  (`apksigner` is in `~/Library/Android/sdk/build-tools/<version>/`.) The release APK talks to production. Debug and
  release builds have the same application id and different signatures: uninstall one before installing the other.

## Scheduled functions and logs

| Function | Schedule | Does |
|---|---|---|
| `advanceOrders` | every 1 minute | moves simulated orders placed → confirmed → shipped (PayPal tracking) → delivered |
| `sweepStuckPurchases` | every 5 minutes | charges or fails purchases stuck > 2 min (idempotent) |
| `checkWatches` | every 60 minutes | price watches → "Price dropped" → purchase through the house rules |

- Jobs: https://console.cloud.google.com/cloudscheduler?project=fetchit-437a0 (last run, status, "Force run").
- Logs (CLI): `fb functions:log --only advanceOrders --project fetchit-437a0`
- Logs (console): Firebase console → Functions → (function) → Logs, or Logs Explorer
  https://console.cloud.google.com/logs/query?project=fetchit-437a0 with
  `resource.type="cloud_run_revision" AND resource.labels.service_name="advanceorders"` (service names are lowercase).
- `fetchItem` logs one line per call with `timings`, the `model` that answered, every attempt
  (`identifyTrace`: primary = Flash-Lite / race = Claude / fallback = 3.6 Flash / low_confidence) and `fallbackUsed`
  (true when anything but Flash-Lite answered).

## Live checks (you run these: they sign in a real test user with email + password)

```bash
cd functions && node test/live.mjs      # link PayPal → buy → order confirmed → cancel/refund → activity
cd functions && node test/measure.mjs   # fetchItem on lamp.png: 1 warm-up + 4 warm calls, timings + top-5 scores
```
They ask for the Firebase Web API key (Project settings → General), the test email and password (hidden).
