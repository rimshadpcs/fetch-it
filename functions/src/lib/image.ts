import sharp from "sharp";

/** Gemini gets up to 1024 px (what the app uploads). */
export const IDENTIFY_EDGE = 1024;
/**
 * Channel3 image search gets 768 px: ~1.3 s vs ~3.5 s at 1024 px, keeping 7 of the top 10 matches
 * (512 px is no faster and keeps only 4/10).
 */
export const SEARCH_EDGE = 768;

const toJpeg = (input: Buffer, edge: number) =>
  sharp(input)
    .rotate() // honour EXIF orientation from phone cameras
    .resize({ width: edge, height: edge, fit: "inside", withoutEnlargement: true })
    .jpeg({ quality: 80 })
    .toBuffer();

/** The app resizes to ~1024 px before upload; anything larger or not JPEG is normalized here. */
export async function prepareImages(input: Buffer): Promise<{ identify: Buffer; search: Buffer }> {
  const meta = await sharp(input).metadata();
  const edge = Math.max(meta.width ?? 0, meta.height ?? 0);
  const [identify, search] = await Promise.all([
    edge > IDENTIFY_EDGE || meta.format !== "jpeg" ? toJpeg(input, IDENTIFY_EDGE) : input,
    edge > SEARCH_EDGE || meta.format !== "jpeg" ? toJpeg(input, SEARCH_EDGE) : input,
  ]);
  return { identify, search };
}
