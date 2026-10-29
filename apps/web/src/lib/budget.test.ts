import { describe, expect, it } from 'vitest'
import { breakdownLines, formatTokens, formatUsd } from './budget'

describe('money and tokens', () => {
  it('writes dollars with the precision a small run needs', () => {
    expect(formatUsd(0)).toBe('free')
    expect(formatUsd(0.0042)).toBe('$0.0042')
    expect(formatUsd(0.456)).toBe('$0.456')
    expect(formatUsd(12.4)).toBe('$12.40')
    expect(formatUsd(null)).toBe('–')
  })

  it('shortens token counts', () => {
    expect([999, 1500, 25_000, 2_400_000].map(formatTokens)).toEqual(['999', '1.5k', '25k', '2.4M'])
  })
})

describe('the cost breakdown', () => {
  it('lists only the lines that have something in them, in SPEC order', () => {
    const lines = breakdownLines({ embeddings: { tokens: 100, calls: 0, usd: 0 }, generation: { tokens: 5000, calls: 10, usd: 0.5 }, judging: { tokens: 0, calls: 0, usd: 0 }, rewriting: { tokens: 300, calls: 10, usd: 0.1 }, synthesis: { tokens: 0, calls: 0, usd: 0 } })
    expect(lines.map((l) => l.label)).toEqual(['Embeddings', 'Answers', 'Query rewriting'])
    expect(breakdownLines(undefined)).toEqual([])
  })
})
