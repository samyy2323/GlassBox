import { describe, expect, it } from 'vitest'
import { expiryText, failureBars, pct, shareUrl, valueWithInterval } from './report'

describe('share links', () => {
  it('is written as an address on this site', () => {
    expect(shareUrl('https://glassbox.example', 'abc')).toBe('https://glassbox.example/r/abc')
  })

  it('says how long a link has left', () => {
    const now = new Date('2026-10-15T12:00:00Z').getTime()
    expect(expiryText('2026-10-15T11:00:00Z', now)).toBe('expired')
    expect(expiryText('2026-10-15T12:30:00Z', now)).toBe('expires within the hour')
    expect(expiryText('2026-10-15T15:00:00Z', now)).toBe('expires in 3 hours')
    expect(expiryText('2026-10-16T12:00:00Z', now)).toBe('expires in 1 day')
    expect(expiryText('2026-10-22T12:00:00Z', now)).toBe('expires in 7 days')
  })
})

describe('numbers in a report', () => {
  it('writes rates with their interval and latencies in milliseconds', () => {
    expect(valueWithInterval({ metric: 'context_pass', value: 0.977, ciLow: 0.93, ciHigh: 1 })).toBe('97.7% [93%, 100%]')
    expect(valueWithInterval({ metric: 'mrr', value: 0.5 })).toBe('50%')
    expect(valueWithInterval({ metric: 'latency_p50_ms', value: 15.6 })).toBe('16 ms')
    expect(pct(undefined)).toBe('–')
    expect(pct(Number.NaN)).toBe('–')
  })

  it('orders the failure bars by count and scales them to the longest', () => {
    expect(failureBars({ RANKING_MISS: 2, CONTEXT_MISS: 4, GENERATION_MISS: 0 })).toEqual([
      { stage: 'context miss', count: 4, width: 1 },
      { stage: 'ranking miss', count: 2, width: 0.5 },
    ])
    expect(failureBars({})).toEqual([])
  })
})
