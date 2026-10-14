import { describe, expect, it } from 'vitest'
import { answerSentences, pageAt, pageBlocks, segmentText, type Highlight } from './highlight'

const text = '0123456789abcdefghij' // 20 chars

describe('segmentText', () => {
  it('returns one plain segment when nothing is highlighted', () => {
    expect(segmentText(text, [])).toEqual([{ start: 0, end: 20, text, kind: undefined, label: undefined }])
  })

  it('cuts around a highlight and keeps the pieces contiguous', () => {
    const segs = segmentText(text, [{ start: 5, end: 10, kind: 'primary', label: 'c1' }])
    expect(segs.map((s) => [s.start, s.end, s.kind])).toEqual([
      [0, 5, undefined],
      [5, 10, 'primary'],
      [10, 20, undefined],
    ])
    expect(segs.map((s) => s.text).join('')).toBe(text)
  })

  it('lets the higher-priority highlight win where two overlap', () => {
    const hs: Highlight[] = [
      { start: 2, end: 12, kind: 'candidate' },
      { start: 6, end: 9, kind: 'primary' },
    ]
    expect(segmentText(text, hs).map((s) => [s.start, s.end, s.kind])).toEqual([
      [0, 2, undefined],
      [2, 6, 'candidate'],
      [6, 9, 'primary'],
      [9, 12, 'candidate'],
      [12, 20, undefined],
    ])
  })

  it('never produces overlapping or missing text, for any combination of highlights', () => {
    const kinds = ['candidate', 'secondary', 'primary'] as const
    for (let a = 0; a < 20; a += 3) {
      for (let b = a + 1; b <= 20; b += 4) {
        for (let c = 0; c < 20; c += 5) {
          const hs: Highlight[] = [
            { start: a, end: b, kind: kinds[a % 3] },
            { start: c, end: Math.min(20, c + 7), kind: kinds[c % 3] },
          ]
          const segs = segmentText(text, hs)
          expect(segs.map((s) => s.text).join('')).toBe(text)
          for (let i = 1; i < segs.length; i++) expect(segs[i].start).toBe(segs[i - 1].end)
        }
      }
    }
  })

  it('clips highlights to the requested window and ignores empty or outside ones', () => {
    const segs = segmentText(text, [{ start: 0, end: 30, kind: 'primary' }, { start: 4, end: 4, kind: 'secondary' }], 5, 15)
    expect(segs).toEqual([{ start: 5, end: 15, text: '56789abcde', kind: 'primary', label: undefined }])
    expect(segmentText(text, [], 8, 8)).toEqual([])
  })
})

describe('pages', () => {
  it('has one block without page starts', () => {
    expect(pageBlocks(20, [])).toEqual([{ page: null, start: 0, end: 20 }])
  })

  it('splits at page starts and skips empty pages', () => {
    expect(pageBlocks(30, [0, 10, 10, 20])).toEqual([
      { page: 1, start: 0, end: 10 },
      { page: 3, start: 10, end: 20 },
      { page: 4, start: 20, end: 30 },
    ])
  })

  it('finds the page of an offset', () => {
    expect(pageAt(5, [0, 10, 20])).toBe(1)
    expect(pageAt(10, [0, 10, 20])).toBe(2)
    expect(pageAt(99, [0, 10, 20])).toBe(3)
    expect(pageAt(5, [])).toBeNull()
  })
})

describe('answerSentences', () => {
  it('attaches citation labels to the sentence they follow', () => {
    const s = answerSentences('Pumps are inspected every 90 days. [c1] The cafeteria opens at 8am [c2]. It serves breakfast.')
    expect(s.map((x) => x.labels)).toEqual([['c1'], ['c2'], []])
  })

  it('handles grouped labels and keeps all the text', () => {
    const answer = 'Both agree [c1, c2]. Done.'
    const s = answerSentences(answer)
    expect(s[0].labels).toEqual(['c1', 'c2'])
    expect(s.map((x) => x.text).join('')).toBe(answer)
  })

  it('returns a single sentence for text without punctuation', () => {
    expect(answerSentences('no punctuation here')).toEqual([{ text: 'no punctuation here', labels: [] }])
  })
})
