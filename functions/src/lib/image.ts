import sharp from "sharp";
import type { Box } from "./gemini.js";

// Images only ever live in memory for the length of a request: nothing here writes to disk or Storage.

/** Gemini gets up to 1024 px (what the app uploads). */
export const IDENTIFY_EDGE = 1024;
/**
 * Channel3 image search gets up to 768 px: ~1.3 s vs ~3.5 s at 1024 px, keeping 7 of the top 10 matches
 * (512 px is no faster and keeps only 4/10).
 */
export const SEARCH_EDGE = 768;
/** Padding around the item's box, as a share of the box size, so edges and context aren't cut off. */
const CROP_PAD = 0.1;

/** The app resizes to ~1024 px before upload; anything larger or not JPEG is normalized here. */
export async function prepareImage(input: Buffer): Promise<{ jpeg: Buffer; width: number; height: number }> {
  const meta = await sharp(input).metadata();
  const edge = Math.max(meta.width ?? 0, meta.height ?? 0);
  if (edge <= IDENTIFY_EDGE && meta.format === "jpeg" && (meta.orientation ?? 1) === 1) {
    return { jpeg: input, width: meta.width!, height: meta.height! };
  }
  const { data, info } = await sharp(input)
    .rotate() // bake in EXIF orientation so Gemini's boxes line up with the pixels we crop
    .resize({ width: IDENTIFY_EDGE, height: IDENTIFY_EDGE, fit: "inside", withoutEnlargement: true })
    .jpeg({ quality: 80 })
    .toBuffer({ resolveWithObject: true });
  return { jpeg: data, width: info.width, height: info.height };
}

/** The chosen item, padded and shrunk for Channel3 image search. */
export async function cropForSearch(img: { jpeg: Buffer; width: number; height: number }, box: Box): Promise<Buffer> {
  const padX = box.width * CROP_PAD;
  const padY = box.height * CROP_PAD;
  const x0 = Math.max(0, box.x - padX);
  const y0 = Math.max(0, box.y - padY);
  const x1 = Math.min(1, box.x + box.width + padX);
  const y1 = Math.min(1, box.y + box.height + padY);
  const left = Math.floor(x0 * img.width);
  const top = Math.floor(y0 * img.height);
  const width = Math.max(1, Math.min(img.width - left, Math.ceil((x1 - x0) * img.width)));
  const height = Math.max(1, Math.min(img.height - top, Math.ceil((y1 - y0) * img.height)));
  return sharp(img.jpeg)
    .extract({ left, top, width, height })
    .resize({ width: SEARCH_EDGE, height: SEARCH_EDGE, fit: "inside", withoutEnlargement: true })
    .jpeg({ quality: 80 })
    .toBuffer();
}
