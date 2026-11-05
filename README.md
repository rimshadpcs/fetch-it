# Fetch It

**You spot it, we fetch it.** Snap a photo of something you want; an AI shopping agent (a dog you name, "Scout" by
default) identifies it, finds where to buy it, and pays with PayPal, but only inside the house rules you set.
Anything outside them asks you first. Entry for the PayPal AI Hackathon (Devpost).

- `app/`: Kotlin Multiplatform app (Compose Multiplatform, Android first)
- `functions/`: Firebase Cloud Functions (TypeScript): Gemini + Claude identify, Channel3 product search, PayPal
  (sandbox) vaulting and payments, the deterministic house-rules engine
- Deploying and secrets: [DEPLOY.md](DEPLOY.md)

Sandbox only: payments use PayPal sandbox accounts and a demo store; no real orders are placed.

## Credits

- Icons: [Oreo Doodle Icons](https://github.com/oreo-design/doodle-icons) by Nicole Tang (free set, MIT).
- Fonts: Gochi Hand, Instrument Serif, Figtree (SIL Open Font License 1.1).

Full notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
