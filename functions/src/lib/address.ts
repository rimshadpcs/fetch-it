// US address validation for saved places (pure: unit-tested in test/places.test.mjs).

/** 50 states, DC and the territories USPS delivers to as domestic mail. */
export const US_STATES = new Set([
  "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
  "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
  "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "DC", "PR", "GU", "VI", "AS", "MP",
]);

export interface PlaceInput {
  label?: unknown;
  fullName?: unknown;
  line1?: unknown;
  line2?: unknown;
  city?: unknown;
  state?: unknown;
  postalCode?: unknown;
  phone?: unknown;
}

export interface ValidPlace {
  label: string;
  fullName: string;
  line1: string;
  line2: string | null;
  city: string;
  state: string;
  postalCode: string;
  countryCode: "US";
  phone: string | null;
}

const clean = (v: unknown) => (typeof v === "string" ? v.trim().replace(/\s+/g, " ") : "");

/**
 * Validates and normalises a US address. Returns the place, or every problem by field (the app shows each one
 * under its field), e.g. { postalCode: "ZIP is 5 digits (or ZIP+4)" }.
 */
export function validatePlace(d: PlaceInput): { place: ValidPlace } | { errors: Record<string, string> } {
  const errors: Record<string, string> = {};
  const label = clean(d.label);
  const fullName = clean(d.fullName);
  const line1 = clean(d.line1);
  const line2 = clean(d.line2);
  const city = clean(d.city);
  const state = clean(d.state).toUpperCase();
  const zip = clean(d.postalCode);
  const phoneRaw = clean(d.phone);

  if (!/^[\p{L}\p{N}' &.-]{1,24}$/u.test(label)) errors.label = "Name it in 1–24 letters (Home, Work, Mum's…)";
  if (fullName.length < 2 || fullName.length > 80 || !/\p{L}/u.test(fullName)) errors.fullName = "Who it's for (2–80 characters)";
  if (line1.length < 3 || line1.length > 100 || !/\d/.test(line1) && !/^p\.?\s*o\.?\s*box/i.test(line1)) {
    errors.line1 = "Street address with a number";
  }
  if (line2.length > 100) errors.line2 = "Up to 100 characters";
  if (city.length < 2 || city.length > 60 || !/^[\p{L} .'-]+$/u.test(city)) errors.city = "City name";
  if (!US_STATES.has(state)) errors.state = "2-letter state (CA, NY…)";
  if (!/^\d{5}(-\d{4})?$/.test(zip)) errors.postalCode = "ZIP is 5 digits (or ZIP+4)";
  let phone: string | null = null;
  if (phoneRaw) {
    const digits = phoneRaw.replace(/[\s().-]/g, "").replace(/^\+?1(?=\d{10}$)/, "");
    if (/^[2-9]\d{2}[2-9]\d{6}$/.test(digits)) phone = `+1${digits}`;
    else errors.phone = "US phone, 10 digits";
  }
  if (Object.keys(errors).length) return { errors };
  return { place: { label, fullName, line1, line2: line2 || null, city, state, postalCode: zip, countryCode: "US", phone } };
}

