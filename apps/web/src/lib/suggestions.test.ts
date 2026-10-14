import { describe, expect, it } from 'vitest'
import { suggestionsFor } from './suggestions'

describe('suggestionsFor', () => {
  it('builds document-specific questions in the phrasing that works with small models', () => {
    const s = suggestionsFor(['handbook', 'fire', 'visitors'])
    expect(s).toHaveLength(4)
    expect(s[0].prompt).toBe('What are the main points of the "handbook" document?')
    expect(s[1].prompt).toContain('"fire"')
    expect(s[2].prompt).toBe('What should I know about "visitors"?')
    expect(s.map((x) => x.prompt).join(' ')).not.toMatch(/summari[sz]e/i)
  })

  it('works with one or two documents without repeating a title three times in a row', () => {
    expect(suggestionsFor(['only'])).toHaveLength(4)
    expect(suggestionsFor(['a', 'b'])).toHaveLength(4)
  })

  it('ignores duplicate titles and offers the generic question when there are no documents', () => {
    expect(suggestionsFor(['same', 'same', 'same'])[0].title).toContain('same')
    const none = suggestionsFor([])
    expect(none).toHaveLength(1)
    expect(none[0].kind).toBe('Find')
  })
})
