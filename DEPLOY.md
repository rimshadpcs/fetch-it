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
