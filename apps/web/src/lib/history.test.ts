import { describe, expect, it } from 'vitest'
import type { TraceSummary } from './api'
import { groupByDay } from './history'

const item = (id: string, iso: string): TraceSummary => ({ id, kind: 'CHAT', status: 'OK', question: id, createdAt: iso })

describe('groupByDay', () => {
  const now = new Date(2026, 9, 14, 15, 0, 0) // 14 Oct 2026, 15:00 local

  it('buckets by local calendar day and drops empty groups', () => {
    const groups = groupByDay(
      [
        item('a', new Date(2026, 9, 14, 9, 0).toISOString()),
        item('b', new Date(2026, 9, 13, 23, 59).toISOString()),
        item('c', new Date(2026, 9, 10, 12, 0).toISOString()),
        item('d', new Date(2026, 8, 1, 12, 0).toISOString()),
      ],
      now,
    )
    expect(groups.map((g) => [g.label, g.items.map((i) => i.id)])).toEqual([
      ['Today', ['a']],
      ['Yesterday', ['b']],
      ['Previous 7 days', ['c']],
      ['Older', ['d']],
    ])
  })

  it('keeps the incoming (newest first) order inside a group and handles no items', () => {
    const groups = groupByDay([item('x', new Date(2026, 9, 14, 14, 0).toISOString()), item('y', new Date(2026, 9, 14, 8, 0).toISOString())], now)
    expect(groups).toHaveLength(1)
    expect(groups[0].items.map((i) => i.id)).toEqual(['x', 'y'])
    expect(groupByDay([], now)).toEqual([])
  })

  it('treats midnight as the start of today', () => {
    expect(groupByDay([item('m', new Date(2026, 9, 14, 0, 0).toISOString())], now)[0].label).toBe('Today')
    expect(groupByDay([item('m', new Date(2026, 9, 13, 23, 59, 59).toISOString())], now)[0].label).toBe('Yesterday')
  })
})
