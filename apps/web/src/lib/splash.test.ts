import { describe, expect, it } from 'vitest'
import { SPLASH_FADE_MS, SPLASH_MS, splashPhase } from './splash'

describe('splashPhase', () => {
  it('is visible at the start, fades for the last stretch and is gone at exactly 3 seconds', () => {
    expect(SPLASH_MS).toBe(3000)
    expect(splashPhase(0)).toBe('show')
    expect(splashPhase(SPLASH_MS - SPLASH_FADE_MS - 1)).toBe('show')
    expect(splashPhase(SPLASH_MS - SPLASH_FADE_MS)).toBe('fade')
    expect(splashPhase(SPLASH_MS - 1)).toBe('fade')
    expect(splashPhase(SPLASH_MS)).toBe('done')
    expect(splashPhase(10_000)).toBe('done')
  })

  it('honours custom durations', () => {
    expect(splashPhase(500, 1000, 200)).toBe('show')
    expect(splashPhase(900, 1000, 200)).toBe('fade')
    expect(splashPhase(1000, 1000, 200)).toBe('done')
  })
})
