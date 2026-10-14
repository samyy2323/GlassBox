import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { RichText, parseBlocks, parseInline } from './richText'

describe('parseInline', () => {
  it('finds bold, italic, code and citations', () => {
    expect(parseInline('a **b** c *d* `e` f [c1]')).toEqual([
      { type: 'text', value: 'a ' },
      { type: 'bold', value: 'b' },
      { type: 'text', value: ' c ' },
      { type: 'italic', value: 'd' },
      { type: 'text', value: ' ' },
      { type: 'code', value: 'e' },
      { type: 'text', value: ' f ' },
      { type: 'cite', labels: ['c1'] },
    ])
  })

  it('splits grouped citation labels', () => {
    expect(parseInline('both [c1, c2]')).toEqual([{ type: 'text', value: 'both ' }, { type: 'cite', labels: ['c1', 'c2'] }])
  })

  it('leaves unterminated or spaced markers as literal text', () => {
    expect(parseInline('2 * 3 = 6 and **open')).toEqual([{ type: 'text', value: '2 * 3 = 6 and **open' }])
    expect(parseInline('array [0] and [cat]')).toEqual([{ type: 'text', value: 'array [0] and [cat]' }])
  })
})

describe('parseBlocks', () => {
  it('separates paragraphs by blank lines and keeps single newlines as line breaks', () => {
    const blocks = parseBlocks('One\nTwo\n\nThree')
    expect(blocks).toHaveLength(2)
    expect(blocks[0]).toMatchObject({ type: 'p', lines: [[{ value: 'One' }], [{ value: 'Two' }]] })
  })

  it('groups bullets and numbered items into lists and switches list type', () => {
    const blocks = parseBlocks('Intro\n- a\n- b\n1. x\n2) y\nOutro')
    expect(blocks.map((b) => b.type)).toEqual(['p', 'ul', 'ol', 'p'])
    expect(blocks[1]).toMatchObject({ items: [[{ value: 'a' }], [{ value: 'b' }]] })
    expect(blocks[2]).toMatchObject({ items: [[{ value: 'x' }], [{ value: 'y' }]] })
  })

  it('handles CRLF and empty input', () => {
    expect(parseBlocks('a\r\n\r\nb')).toHaveLength(2)
    expect(parseBlocks('')).toEqual([])
  })
})

describe('RichText', () => {
  const html = (text: string) => renderToStaticMarkup(<RichText text={text} renderCite={(l) => <sup data-cite={l}>{l}</sup>} />)

  it('renders citations through the callback', () => {
    expect(html('Fact [c1].')).toContain('<sup data-cite="c1">c1</sup>')
  })

  it('never turns text into markup, links or images', () => {
    const out = html('<img src=x onerror=alert(1)> <script>alert(2)</script> [link](https://evil.example) ![p](https://evil.example/p.png)')
    expect(out).not.toContain('<img')
    expect(out).not.toContain('<script')
    expect(out).not.toContain('<a ')
    expect(out).toContain('&lt;img src=x onerror=alert(1)&gt;')
  })

  it('renders lists and emphasis as elements', () => {
    const out = html('- **bold** item\n- `code` item')
    expect(out).toContain('<ul>')
    expect(out).toContain('<strong class="font-semibold">bold</strong>')
    expect(out).toContain('<code>code</code>')
  })
})
