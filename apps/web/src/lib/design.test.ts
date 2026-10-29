import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { contrastRatio, over, parseTriplet, type Rgb } from './contrast'
import { countUpValue, easeOutExpo } from './useCountUp'

const css = readFileSync(new URL('../index.css', import.meta.url), 'utf8')
const indexHtml = readFileSync(new URL('../../index.html', import.meta.url), 'utf8')

/** The declarations of one rule of index.css, found by its selector. */
function tokens(selector: string): Record<string, string> {
  const start = css.indexOf(`${selector} {`)
  if (start < 0) throw new Error(`no rule ${selector}`)
  const body = css.slice(css.indexOf('{', start) + 1, css.indexOf('\n}', start))
  const out: Record<string, string> = {}
  for (const m of body.matchAll(/--([a-z0-9-]+):\s*([^;]+);/g)) out[m[1]] = m[2].trim()
  return out
}

describe('the colour tokens stay readable (WCAG AA)', () => {
  for (const [name, selector] of [['dark', '.dark'], ['light', ':root']] as const) {
    const t = tokens(selector)
    const bg = parseTriplet(t['bg-rgb'])
    // glass panels sit on the page: the brightest the background gets is the panel's own fill painted over it, plus the strongest orb behind it
    const panel = (alpha: number): Rgb => over(name === 'dark' ? [255, 255, 255] : [255, 255, 255], alpha, bg)
    const fills = name === 'dark' ? [0.045, 0.08, 0.12] : [0.62, 0.82, 0.9]

    it(`${name}: body text on the page and on glass`, () => {
      const fg = parseTriplet(t['fg-rgb'])
      expect(contrastRatio(fg, bg)).toBeGreaterThanOrEqual(7)
      for (const a of fills) expect(contrastRatio(fg, panel(a))).toBeGreaterThanOrEqual(7)
    })

    it(`${name}: muted text on the page and on glass`, () => {
      const muted = parseTriplet(t['muted-rgb'])
      expect(contrastRatio(muted, bg)).toBeGreaterThanOrEqual(4.5)
      for (const a of fills) expect(contrastRatio(muted, panel(a))).toBeGreaterThanOrEqual(4.5)
    })

    it(`${name}: accent, success and warning text`, () => {
      for (const key of ['accent-rgb', 'accent2-rgb', 'ok-rgb', 'warn-rgb', 'danger-rgb']) {
        const colour = parseTriplet(t[key])
        for (const a of fills) expect(contrastRatio(colour, panel(a)), `${key} on glass ${a}`).toBeGreaterThanOrEqual(4.5)
      }
    })
  }

  it('the ink on the prism gradient is readable at every stop', () => {
    const ink: Rgb = [7, 8, 16]
    for (const stop of [[103, 232, 249], [167, 139, 250], [240, 171, 252]] as Rgb[]) expect(contrastRatio(ink, stop)).toBeGreaterThanOrEqual(7)
  })

  it('the dark theme is the home theme', () => {
    expect(indexHtml).toMatch(/<html[^>]*class="dark"/)
  })
})

describe('count up', () => {
  it('starts at the start, ends exactly at the end, and only moves forward', () => {
    expect(countUpValue(0, 80, 0, 900)).toBe(0)
    expect(countUpValue(0, 80, 900, 900)).toBe(80)
    expect(countUpValue(0, 80, 5000, 900)).toBe(80)
    let last = -1
    for (let t = 0; t <= 900; t += 50) {
      const v = countUpValue(0, 80, t, 900)
      expect(v).toBeGreaterThanOrEqual(last)
      last = v
    }
  })

  it('counts down too, and a zero duration jumps', () => {
    expect(countUpValue(100, 40, 450, 900)).toBeLessThan(100)
    expect(countUpValue(100, 40, 450, 900)).toBeGreaterThan(40)
    expect(countUpValue(0, 7, 0, 0)).toBe(7)
  })

  it('eases out: more than half way after half the time', () => {
    expect(easeOutExpo(0.5)).toBeGreaterThan(0.9)
    expect(easeOutExpo(1)).toBe(1)
    expect(easeOutExpo(-1)).toBe(0)
  })
})
