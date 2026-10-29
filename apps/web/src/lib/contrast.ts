/** WCAG 2 contrast, for a test that keeps the colour tokens readable on the glass. Colours are [r, g, b] in 0..255. */
export type Rgb = [number, number, number]

function channel(v: number): number {
  const s = v / 255
  return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4)
}

export function luminance([r, g, b]: Rgb): number {
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}

export function contrastRatio(a: Rgb, b: Rgb): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/** `a` painted at `alpha` (0..1) over `b`. */
export function over(a: Rgb, alpha: number, b: Rgb): Rgb {
  return [0, 1, 2].map((i) => a[i] * alpha + b[i] * (1 - alpha)) as Rgb
}

/** "167 139 250" to [167, 139, 250]. */
export function parseTriplet(text: string): Rgb {
  const parts = text.trim().split(/\s+/).map(Number)
  if (parts.length !== 3 || parts.some((n) => !Number.isFinite(n))) throw new Error(`not an rgb triplet: ${text}`)
  return parts as Rgb
}
