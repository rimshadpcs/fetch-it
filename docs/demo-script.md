# Fetch It: screen-recording checklist + script

Record every scene below as its own clip, then cut the main video down (Devpost videos are usually ~3 min: check the
rules page). Scenes marked **(main)** belong in the final cut; **(extra)** clips are for a longer cut or the README.
Say the lines in *italics* (voice-over) or put them on screen as captions.

---

## A. Before you hit record

### Phone / emulator
- [ ] Latest build installed: `cd app && ./gradlew :androidApp:installDebug` (or the signed release APK)
- [ ] Do Not Disturb on, but **Fetch It notifications allowed** (approval + price-drop pushes are part of the demo)
- [ ] Clock looks normal, battery icon full or hidden, Wi-Fi strong
- [ ] Animations on (Settings → Accessibility → Remove animations **off**), system font size default
- [ ] Screen recorder: 1080p, 30/60 fps, show touches **on** (Developer options → Show taps)
- [ ] Sound off on the phone; voice-over recorded separately

### Accounts and data
- [ ] Fresh install for onboarding (clear app data). Keep a second, already-onboarded state for the rest, or just
      continue from onboarding
- [ ] PayPal sandbox buyer login ready to type yourself (fetchBuyer): **in a private window / logged out of live PayPal**
- [ ] Sandbox merchant dashboard (fetchMerchant) open in a browser tab, to show the money landing (scene 14)
- [ ] Firebase console tab open: Firestore → `users/<uid>/watches`, and Cloud Scheduler (for the price-drop scene)

### Rules to set in onboarding (so every outcome shows up)
| Setting | Value | Why |
|---|---|---|
| Monthly budget | **$200** | a $300+ item gets BLOCKED |
| Auto-buy up to | **$75** | a $40 item auto-buys, a $99 one asks |
| Gifts | **on, $50** | gift scene |
| Ask before a new store | **off** | keeps the first buy an auto-buy |
| Categories | **Home & Garden, Clothing & Accessories** (not Health & Beauty) | perfume asks → "Always allow" |

### Photos and links on the phone (no brand logos visible: hackathon rule)
- [ ] **Lamp photo** (glass mushroom / bedside lamp): one item → straight to results, cheap auto-buy (~$40)
- [ ] **Outfit / room photo with 3+ items** (hoodie, cap, pants): "Which one?"
- [ ] **Perfume bottle photo** (unbranded): category outside the rules
- [ ] **Screenshot** of a product (for Upload)
- [ ] **Pinterest pin** link to share (e.g. https://in.pinterest.com/pin/516717757264399280/)
- [ ] A saved place for **Mom** (You → Saved places) if you're not adding it on camera

---

## B. Script (scene by scene)

### 1. Hook (main, ~10 s)
Show: Welcome screen, Scout's face rising, "You spot it." / "We fetch it." tape strips slapping on.
*"You see something you love: on a friend, in a café, on Pinterest. Fetch It's dog, Scout, finds it and buys it with
PayPal, but only inside house rules you set."*

### 2. Link PayPal once (main, ~15 s)
Tap Get started → **Link PayPal** → PayPal sandbox sheet → log in as fetchBuyer → approve → back in the app,
"LINKED ✓" stamps, Scout hops.
*"You approve PayPal once. That saved PayPal is what lets Scout pay later without sending you to a checkout."*
- [ ] Nothing was charged (say it)

### 3. Where should I deliver? (main, ~8 s)
PayPal's address appears as a paper note "from PayPal" → **Yes, deliver here** → pin drops.
(extra: "Use a different address" → the form, field errors)

### 4. House rules (main, ~20 s): the heart of the pitch
Spin the **budget drum** to $200, **auto-buy** to $75 (watch the mood caption + bar fill). Turn **Gifts** on → $50.
Show the three ask switches. Tick categories (rows fill black with a ✓, counter updates). **Looks good.**
*"These rules are checked in plain code before every payment, never by the AI. The AI proposes; the rules decide."*

### 5. Sizes + name your dog (main, ~10 s)
Pick sizes (pen circle), flip US → UK → EU on shoes. Next: name the dog (keep Scout or rename), Scout tilts his head
while you type → **Start fetching**: Scout runs off, camera slides up.

### 6. Fetch screen tour (extra, ~5 s)
Header: date, heart (saved) and price-tag (watches) buttons with counts, "$200 left this month" pill, snaps pill,
the boiling camera button in the tab bar.

### 7. Snap → results → AUTO-BUY with undo (main, ~25 s)
Camera → photograph the **lamp** → "I think it's a…" → **Look what I found**: 5 offers with live price, store,
my pick ★. Point at: live price checked, "auto-buys" vs "I'd ask" labels.
Tap the ~$40 offer → **Buy** → decision streams in → **Fetched** stamp + 5 s **Undo** countdown → let it run out.
Bought screen: "Arrives <day>", "Demo store · sandbox".
*"Under my $75 limit, inside budget, a category I allowed: Scout just buys it, with five seconds to undo."*

### 8. Undo (extra, ~8 s)
Same lamp again → Buy → tap **Undo** inside 5 s → Undone, nothing charged, budget back.

### 9. Needs your OK → fingerprint (main, ~20 s)
Fetch a **~$99 lamp** (over $75) → Buy → **Just checking…** sheet: "$24 over your $75 limit" → **Approve with
fingerprint** (pulsing) → biometric prompt → charged.
Also show the push: lock or background the app before Buy → "Needs your OK: … · $99" notification → tap → sheet.
*"Over the limit, Scout asks first. One fingerprint and it's paid."*

### 10. Outside the categories → Always allow (main, ~12 s)
Fetch the **perfume** → Buy → "Health & Beauty isn't in your allowed categories" → turn on **Always allow Health &
Beauty** → approve. Show Activity: the rules-updated entry.

### 11. BLOCKED, nothing charged (main, ~10 s)
Pick an offer **over your remaining budget** (e.g. a $300+ lamp) → Buy → **Blocked** with the reason → **Change rule**
jumps to that section of Rules, outlined.
*"Against a hard rule, Scout doesn't buy. Nothing is charged, and he tells you why."*

### 12. Which one? (main, ~12 s)
Photo with several items → Scout circles each with a pen loop → tap the hoodie → results with colour + sizes, your
saved size pre-picked ("Your size M is in stock"). Tabs if you pick more than one.

### 13. Results extras (main, ~20 s: pick 2–3; rest as extra)
- [ ] **Heart** a result (pop + burst) → appears in Saved for later
- [ ] **More matches**: swipe the polaroid fan, tap one to pick it
- [ ] **Ship to ▾**: switch the place for this buy
- [ ] **Watch price**: set a target 10–20% under → pill turns black "watching ≤ $X"
- [ ] **Gift**: tap gift → pick **Mom** → write "Happy birthday!" → Buy bar reads "gift to ▾ Mom" → Buy →
      Bought says "a gift for Mom", and the monthly budget chip didn't move (gift budget instead)

### 14. PayPal side: the money is real (sandbox) (main, ~10 s)
Browser: sandbox merchant account (fetchMerchant) → activity shows the payments; open one: shipping address = your
saved place, then later the **tracking number** added by Fetch It.
*"Every buy is a real PayPal sandbox order on the saved PayPal: vaulted payment, our address, shipment tracking."*

### 15. Order → shipped → cancel & refund (main, ~20 s)
Activity → a paid card → **Order**: the route animation (store → home), timeline: placed → confirmed (30 s) →
shipped (2 min, DEMO tracking). On a *fresh* order before shipping: **Cancel order** → tap again "cancel and refund
$X" → "Cancelled. $X refunded to your PayPal", budget restored.
(Tip: record placed → shipped once and cut the wait.)

### 16. Goes well with (main, ~12 s)
Bought screen → **what goes with it? ›** → after a few seconds 3 ideas × 2 offers (e.g. bulbs, tray, candles for the
lamp) → tap one → its Buy panel (same house rules).

### 17. Price watch → drop → push → auto-buy (main, ~20 s)
You → **Price watches**: sparkline, high/low, Scout's verdict ("I'd wait for a drop").
To trigger on camera: Firestore → `users/<uid>/watches/<id>` → set `targetPrice` above `lastPrice` (for a buy) or
`startPrice` to 999 (for a "Price dropped" push only) → Cloud Scheduler → **Force run** the checkWatches job →
notification "Price dropped: … · $X" → tap → Price watches / the purchase. Activity card shows the old price struck
through and the new one counting down.
*"Watching runs while you sleep. When the price hits your target, the same house rules apply."*

### 18. Activity feed (main, ~8 s)
Scroll Activity: Bought, Needs your OK (red dot on the tab), Blocked, Undone, Price dropped, Shipped, gift card.
Each card says *why* (the rule that passed or failed).

### 19. Share into Fetch It (main, ~12 s)
Pinterest app → a pin → Share → **Fetch it** → "Getting the photo from Pinterest…" → results.
(extra: Gallery photo → Share → Fetch it; Instagram post → "Screenshot the post and share that instead.")

### 20. Upload a screenshot (extra, ~6 s)
Fetch → Upload → pick the product screenshot → results.

### 21. Snaps album + Search again (extra, ~10 s)
Snaps pill → stamp album → open a past snap → **Search again** → "2 new · 1 cheaper since Nov 5", "new!" tape and
struck-through old prices.

### 22. Saved for later (extra, ~8 s)
Heart button in the header → Saved → tap an item → live price, sizes, Buy (and watch price from here too).

### 23. You tab (extra, ~10 s)
Rules summary → Rules; Saved places (make default, add Mom, edit); Sizes; rename the fetcher (sticks); privacy text
("photos stay on your phone"). Long-press the dog → **Motion lab** (fun B-roll).

### 24. Close (main, ~8 s)
Back on Fetch, Scout peeking over the viewfinder.
*"You spot it, Scout fetches it: PayPal pays, your rules decide. Fetch It."*

---

## C. Don't-miss checklist (tick while recording)

**Onboarding**
- [ ] Welcome animation · [ ] PayPal link + LINKED stamp · [ ] PayPal address suggested → confirmed
- [ ] Rules drums · [ ] Gift budget on · [ ] Ask switches · [ ] Categories · [ ] Sizes (US/UK/EU flip) · [ ] Name the dog

**Fetching**
- [ ] Camera snap · [ ] Upload · [ ] Which one? (several items) · [ ] Live price + sizes + colours · [ ] My pick
- [ ] More matches fan · [ ] Heart / Saved for later · [ ] Ship-to sheet · [ ] Share from Pinterest · [ ] Share a photo
- [ ] Snaps album + Search again

**The three outcomes (house rules)**
- [ ] AUTO_BUY + 5 s undo · [ ] Undo · [ ] NEEDS_APPROVAL + fingerprint · [ ] push "Needs your OK"
- [ ] Category → Always allow · [ ] BLOCKED + Change rule · [ ] (optional) size not given → asks

**After the buy**
- [ ] Bought screen "Arrives …" · [ ] Order route + timeline · [ ] Shipped with tracking · [ ] Cancel → refund
- [ ] Goes well with · [ ] Gift for Mom (gift budget, note) · [ ] PayPal sandbox dashboard shows payment + tracking

**Watching**
- [ ] Watch price sheet · [ ] Price watches screen + sparkline/verdict · [ ] Drop push · [ ] Triggered auto-buy
      with struck-through price in Activity

**Polish B-roll**
- [ ] Scout moods (wag, sniffing, peeks over the viewfinder and Buy bar) · [ ] Paw loader · [ ] Motion lab
- [ ] Budget chip updating after a buy · [ ] Red dot on Activity tab

**Say at least once**
- [ ] "Rules are checked in code before any payment; the AI never decides to pay"
- [ ] "PayPal vaulting: approve once, Scout pays later with no redirect"
- [ ] "Sandbox: charges go to our demo merchant; no real store order is placed" (honesty for judges)
- [ ] "Products and live prices come from Channel3; Gemini and Claude identify the item"

---

## D. After recording
- [ ] Cancel / refund leftover test orders so the month's budget is clean for judges
- [ ] Turn "Ask before a new store" back to how you want judges to see it
- [ ] Reset any Firestore edits made for the watch scene (targetPrice / startPrice)
