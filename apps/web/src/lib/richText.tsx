import { Fragment, type ReactNode } from 'react'

// A deliberately small, safe formatter for model answers: paragraphs, bullet and numbered lists, **bold**, *italic*, `code`,
// and [c1] citation markers. It builds React elements from text only: there is no HTML parsing, no links and no images, so a
// document that talks the model into emitting markup cannot make the page load anything or run anything.

export type Inline =
  | { type: 'text'; value: string }
  | { type: 'bold'; value: string }
  | { type: 'italic'; value: string }
  | { type: 'code'; value: string }
  | { type: 'cite'; labels: string[] }

export type Block =
  | { type: 'p'; lines: Inline[][] }
  | { type: 'ul'; items: Inline[][] }
  | { type: 'ol'; items: Inline[][] }

const INLINE = /(\*\*[^*\n]+\*\*|`[^`\n]+`|\*[^*\s][^*\n]*\*|\[c\d+(?:\s*,\s*c\d+)*\])/g

// eslint-disable-next-line react-refresh/only-export-components
export function parseInline(text: string): Inline[] {
  const out: Inline[] = []
  for (const part of text.split(INLINE)) {
    if (!part) continue
    if (part.startsWith('**') && part.endsWith('**') && part.length > 4) out.push({ type: 'bold', value: part.slice(2, -2) })
    else if (part.startsWith('`') && part.endsWith('`') && part.length > 2) out.push({ type: 'code', value: part.slice(1, -1) })
    else if (/^\[c\d/.test(part) && part.endsWith(']')) out.push({ type: 'cite', labels: part.slice(1, -1).split(/\s*,\s*/) })
    else if (part.startsWith('*') && part.endsWith('*') && part.length > 2) out.push({ type: 'italic', value: part.slice(1, -1) })
    else out.push({ type: 'text', value: part })
  }
  return out
}

const BULLET = /^\s*[-*•]\s+(.*)$/
const NUMBERED = /^\s*\d+[.)]\s+(.*)$/

// eslint-disable-next-line react-refresh/only-export-components
export function parseBlocks(text: string): Block[] {
  const blocks: Block[] = []
  let para: string[] = []
  let list: { type: 'ul' | 'ol'; items: string[] } | null = null

  const flushPara = () => {
    if (para.length) blocks.push({ type: 'p', lines: para.map(parseInline) })
    para = []
  }
  const flushList = () => {
    if (list) blocks.push({ type: list.type, items: list.items.map(parseInline) })
    list = null
  }

  for (const line of text.replace(/\r\n?/g, '\n').split('\n')) {
    const bullet = BULLET.exec(line)
    const numbered = bullet ? null : NUMBERED.exec(line)
    if (bullet || numbered) {
      flushPara()
      const type = bullet ? 'ul' : 'ol'
      if (list && list.type !== type) flushList()
      list ??= { type, items: [] }
      list.items.push((bullet ?? numbered)![1])
    } else if (line.trim() === '') {
      flushPara()
      flushList()
    } else {
      flushList()
      para.push(line.trim())
    }
  }
  flushPara()
  flushList()
  return blocks
}

interface Props {
  text: string
  /** Renders a citation marker; labels the answer cites but the caller does not know are the caller's to flag. */
  renderCite: (label: string) => ReactNode
}

function Inlines({ nodes, renderCite }: { nodes: Inline[]; renderCite: Props['renderCite'] }) {
  return (
    <>
      {nodes.map((n, i) => {
        switch (n.type) {
          case 'bold':
            return <strong key={i} className="font-semibold">{n.value}</strong>
          case 'italic':
            return <em key={i}>{n.value}</em>
          case 'code':
            return <code key={i}>{n.value}</code>
          case 'cite':
            return <Fragment key={i}>{n.labels.map((l) => <Fragment key={l}>{renderCite(l)}</Fragment>)}</Fragment>
          default:
            return <Fragment key={i}>{n.value}</Fragment>
        }
      })}
    </>
  )
}

export function RichText({ text, renderCite }: Props) {
  return (
    <div className="prose-answer">
      {parseBlocks(text).map((b, i) =>
        b.type === 'p' ? (
          <p key={i}>
            {b.lines.map((line, j) => (
              <Fragment key={j}>
                {j > 0 && <br />}
                <Inlines nodes={line} renderCite={renderCite} />
              </Fragment>
            ))}
          </p>
        ) : b.type === 'ul' ? (
          <ul key={i}>{b.items.map((item, j) => <li key={j}><Inlines nodes={item} renderCite={renderCite} /></li>)}</ul>
        ) : (
          <ol key={i}>{b.items.map((item, j) => <li key={j}><Inlines nodes={item} renderCite={renderCite} /></li>)}</ol>
        ),
      )}
    </div>
  )
}
