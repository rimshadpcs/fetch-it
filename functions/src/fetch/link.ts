// Pure helpers for "share a link to Fetch It" (unit-tested): which kind of link it is, and the preview image on a page.

export type LinkKind = { kind: "instagram" } | { kind: "pinterest"; pinId: string | null } | { kind: "page" };

const isPinterestHost = (h: string) => h === "pin.it" || /(^|\.)pinterest\.[a-z.]{2,8}$/.test(h);
const isInstagramHost = (h: string) => h === "instagr.am" || /(^|\.)instagram\.com$/.test(h);

/** The first http(s) link in shared text ("Look at this! https://pin.it/abc"), or null. */
export function firstUrl(text: string): URL | null {
  const m = text.match(/https?:\/\/[^\s<>"']+/i);
  if (!m) return null;
  try {
    const u = new URL(m[0].replace(/[).,!?]+$/, ""));
    return u.protocol === "https:" || u.protocol === "http:" ? u : null;
  } catch {
    return null;
  }
}

export function classify(u: URL): LinkKind {
  const host = u.hostname.toLowerCase();
  if (isInstagramHost(host)) return { kind: "instagram" };
  if (isPinterestHost(host)) return { kind: "pinterest", pinId: u.pathname.match(/\/pin\/(\d{5,})/)?.[1] ?? null };
  return { kind: "page" };
}

export const isPinterest = (u: URL) => isPinterestHost(u.hostname.toLowerCase());

/** Pinterest's oEmbed thumbnail is 236 px wide; the same path at 736x is the pin at a size worth identifying. */
export const biggerPin = (thumb: string) => thumb.replace(/\/\d+x\//, "/736x/");

const decode = (s: string) =>
  s.replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#39;|&#x27;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&#x2F;|&#47;/g, "/");

/** og:image (or twitter:image) from a page's <meta> tags, in any attribute order, resolved against the page URL. */
export function previewImage(html: string, page: URL): URL | null {
  const wanted = ["og:image:secure_url", "og:image:url", "og:image", "twitter:image", "twitter:image:src"];
  const found = new Map<string, string>();
  for (const tag of html.match(/<meta\b[^>]*>/gi) ?? []) {
    const attrs = new Map<string, string>();
    for (const a of tag.matchAll(/([a-zA-Z:-]+)\s*=\s*("([^"]*)"|'([^']*)')/g)) attrs.set(a[1].toLowerCase(), a[3] ?? a[4] ?? "");
    const key = (attrs.get("property") ?? attrs.get("name") ?? "").toLowerCase();
    const content = attrs.get("content");
    if (content && wanted.includes(key) && !found.has(key)) found.set(key, decode(content.trim()));
  }
  for (const k of wanted) {
    const v = found.get(k);
    if (!v) continue;
    try {
      const u = new URL(v, page);
      if (u.protocol === "https:" || u.protocol === "http:") return u;
    } catch { /* next */ }
  }
  return null;
}

/** Addresses a server-side fetch must never reach (loopback, private, link-local incl. the metadata server, CGNAT). */
export function isPrivateAddress(ip: string): boolean {
  const v4 = ip.startsWith("::ffff:") ? ip.slice(7) : ip;
  const m = v4.match(/^(\d+)\.(\d+)\.(\d+)\.(\d+)$/);
  if (m) {
    const [a, b] = [Number(m[1]), Number(m[2])];
    return a === 0 || a === 10 || a === 127 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127) || a >= 224;
  }
  const h = ip.toLowerCase();
  return h === "::" || h === "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8") || h.startsWith("fe9") ||
    h.startsWith("fea") || h.startsWith("feb") || h.startsWith("ff");
}
