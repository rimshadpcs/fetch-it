import type { C3OptionValue, C3Variants } from "../lib/channel3.js";

// Option names vary by store. "Size Type" (Petite/Regular/Tall) is not the size list.
export const sizeOptionOf = (v: C3Variants | null | undefined) =>
  v?.options.find((o) => /\bsizes?\b/i.test(o.name) && !/type|fit|range/i.test(o.name));
export const colorOptionOf = (v: C3Variants | null | undefined) => v?.options.find((o) => /colou?r/i.test(o.name));

/**
 * Store colour names → base families, so Gemini's "brown" finds "Coco" or "Truffle".
 * Base words map to themselves; anything unlisted only matches on the literal word.
 */
const FAMILY: Record<string, string> = {
  // brown
  brown: "brown", coco: "brown", cocoa: "brown", chocolate: "brown", truffle: "brown", mocha: "brown",
  espresso: "brown", coffee: "brown", chestnut: "brown", walnut: "brown", cognac: "brown", camel: "brown",
  tan: "brown", taupe: "brown", umber: "brown", sepia: "brown", cinnamon: "brown", mahogany: "brown",
  bark: "brown", java: "brown", caramel: "brown", toffee: "brown", palomino: "brown", terra: "brown",
  // beige / cream / white
  beige: "beige", khaki: "beige", sand: "beige", stone: "beige", oatmeal: "beige", oat: "beige",
  ecru: "beige", natural: "beige", fawn: "beige", biscuit: "beige", latte: "beige",
  cream: "white", ivory: "white", bone: "white", white: "white", vanilla: "white", chalk: "white",
  snow: "white", optic: "white", pearl: "white", milk: "white", eggshell: "white",
  // grey / black
  grey: "grey", gray: "grey", heather: "grey", charcoal: "grey", cement: "grey", ash: "grey", slate: "grey",
  graphite: "grey", smoke: "grey", pewter: "grey", silver: "grey", steel: "grey", concrete: "grey",
  black: "black", jet: "black", onyx: "black", ebony: "black", noir: "black", raven: "black", ink: "black",
  // blue
  blue: "blue", navy: "blue", indigo: "blue", denim: "blue", cobalt: "blue", sky: "blue", azure: "blue",
  oxford: "blue", royal: "blue", teal: "blue", aqua: "blue", marine: "blue", midnight: "blue", chambray: "blue",
  // green
  green: "green", olive: "green", sage: "green", forest: "green", khakigreen: "green", moss: "green",
  emerald: "green", mint: "green", seagrass: "green", army: "green", hunter: "green", pine: "green",
  jade: "green", lime: "green", fern: "green",
  // red / pink / purple
  red: "red", burgundy: "red", maroon: "red", wine: "red", crimson: "red", cherry: "red", brick: "red",
  rust: "red", scarlet: "red", oxblood: "red", merlot: "red", cranberry: "red",
  pink: "pink", rose: "pink", blush: "pink", fuchsia: "pink", magenta: "pink", salmon: "pink", coral: "pink",
  purple: "purple", lavender: "purple", lilac: "purple", violet: "purple", plum: "purple", mauve: "purple",
  // yellow / orange
  yellow: "yellow", mustard: "yellow", lemon: "yellow", gold: "yellow", butter: "yellow", ochre: "yellow",
  orange: "orange", tangerine: "orange", apricot: "orange", peach: "orange", amber: "orange", burnt: "orange",
};

const tokens = (s: string) => s.toLowerCase().split(/[^a-z]+/).filter(Boolean);
const families = (s: string) => new Set(tokens(s).map((t) => FAMILY[t] ?? t));

/**
 * The colour option value that best matches what Gemini saw, or null to keep the store's default.
 * Score = shared colour families; the first colour Gemini lists counts double (it's the dominant one).
 * Ties go to a literal colour word ("Brown" over "Taupe Rose"), then to labels with fewer other colours.
 */
export function chooseColor(variants: C3Variants | null | undefined, wanted: string[]): string | null {
  const values = colorOptionOf(variants)?.values.filter((v) => v.exists);
  if (!values?.length || !wanted.length) return null;
  const weights = new Map<string, number>();
  wanted.forEach((w, i) => families(w).forEach((f) => weights.set(f, Math.max(weights.get(f) ?? 0, i === 0 ? 2 : 1))));
  const literal = new Set(wanted.flatMap(tokens));
  let best: { label: string; score: number } | null = null;
  for (const v of values) {
    const fams = families(v.label);
    let score = 0;
    fams.forEach((f) => (score += weights.get(f) ?? 0));
    if (score === 0) continue;
    if (tokens(v.label).some((t) => literal.has(t))) score += 0.5;
    score -= 0.1 * [...fams].filter((f) => !weights.has(f)).length;
    if (!best || score > best.score) best = { label: v.label, score };
  }
  return best?.label ?? null;
}

/** Canonical letter sizes so "SML", "Small", "S 6-8" and "S" all match a saved "S". Numeric sizes pass through. */
const LETTER: Record<string, string> = {
  xxs: "XXS", "2xs": "XXS", xs: "XS", xsml: "XS", xsmall: "XS", extrasmall: "XS",
  s: "S", sm: "S", sml: "S", small: "S",
  m: "M", md: "M", med: "M", medium: "M",
  l: "L", lg: "L", lrg: "L", large: "L",
  xl: "XL", xlg: "XL", xlarge: "XL", extralarge: "XL",
  xxl: "XXL", "2xl": "XXL", xxlg: "XXL", xxlarge: "XXL",
  xxxl: "XXXL", "3xl": "XXXL", xxxlarge: "XXXL",
  "4xl": "4XL", xxxxl: "4XL",
  // Plus sizes are their own range, not XL/XXL.
  "1x": "1X", "2x": "2X", "3x": "3X", "4x": "4X",
};

export function normalizeSize(label: string): string | null {
  // Hat and shoe sizes with fractions: "7 1/4", "7¼", "10.5"
  const frac = label.trim().match(/^(\d+)(?:\s+(\d)\/(\d)|([¼½¾]))?$/);
  if (frac) {
    const extra = frac[2] ? Number(frac[2]) / Number(frac[3]) : frac[4] ? { "¼": 0.25, "½": 0.5, "¾": 0.75 }[frac[4]]! : 0;
    return String(Number(frac[1]) + extra);
  }
  const first = label.trim().split(/\s+/)[0] ?? "";
  // "US 4", "EU 42" → the number
  const region = /^(us|uk|eu|it|fr|jp)$/i.test(first) ? label.trim().split(/\s+/)[1] : undefined;
  const raw = (region ?? first).toLowerCase();
  const compact = label.toLowerCase().replace(/[^a-z0-9]/g, "");
  if (LETTER[compact]) return LETTER[compact];
  if (LETTER[raw.replace(/[^a-z0-9]/g, "")]) return LETTER[raw.replace(/[^a-z0-9]/g, "")];
  if (/^\d+(\.\d+)?$/.test(raw)) return raw;
  return null;
}

const UNBUYABLE = new Set(["OutOfStock", "SoldOut", "Discontinued"]);
/** Sizes that exist in the selected colour and aren't known to be sold out (null stock = unknown, kept). */
export const buyableSizes = (values: C3OptionValue[]) =>
  values.filter((v) => v.exists && !(v.available && UNBUYABLE.has(v.available)));
