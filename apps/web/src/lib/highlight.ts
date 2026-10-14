// Pure helpers for showing a document with highlighted character ranges. Everything is in the offsets of the document's
// stored normalized text (the same offsets retrieval, chunks and gold spans use), so highlights are exact.

export type HighlightKind = 'primary' | 'secondary' | 'candidate'

export interface Highlight {
  start: number
  end: number
  kind: HighlightKind
  /** Shown as a tooltip, e.g. "c1" or "candidate #4". */
  label?: string
}

export interface Segment {
  start: number
  end: number
  text: string
  kind?: HighlightKind
  label?: string
}

const PRIORITY: Record<HighlightKind, number> = { candidate: 1, secondary: 2, primary: 3 }

/**
 * Splits text[from, to) into consecutive segments, each either plain or covered by the highest-priority highlight
 * overlapping it. Segments never overlap and together cover the whole range.
 */
export function segmentText(text: string, highlights: Highlight[], from = 0, to = text.length): Segment[] {
  const lo = Math.max(0, from)
  const hi = Math.min(text.length, to)
  if (hi <= lo) return []
  const relevant = highlights.filter((h) => h.end > lo && h.start < hi && h.end > h.start)

  const cuts = new Set<number>([lo, hi])
  for (const h of relevant) {
    cuts.add(Math.max(lo, h.start))
    cuts.add(Math.min(hi, h.end))
  }
  const points = [...cuts].sort((a, b) => a - b)

  const out: Segment[] = []
  for (let i = 0; i + 1 < points.length; i++) {
    const start = points[i]
    const end = points[i + 1]
    let best: Highlight | undefined
    for (const h of relevant) {
      if (h.start <= start && h.end >= end && (!best || PRIORITY[h.kind] > PRIORITY[best.kind])) best = h
    }
    out.push({ start, end, text: text.slice(start, end), kind: best?.kind, label: best?.label })
  }
  return out
}

export interface PageBlock {
  /** 1-based, or null when the document has no pages (Markdown, HTML, DOCX). */
  page: number | null
  start: number
  end: number
}

/** Splits the text at page boundaries. pageStarts[i] is the offset where page i+1 begins. */
export function pageBlocks(textLength: number, pageStarts: number[]): PageBlock[] {
  if (pageStarts.length === 0) return [{ page: null, start: 0, end: textLength }]
  const blocks: PageBlock[] = []
  for (let i = 0; i < pageStarts.length; i++) {
    const start = i === 0 ? 0 : pageStarts[i]
    const end = i + 1 < pageStarts.length ? pageStarts[i + 1] : textLength
    // Pages without text share an offset with the next page: keep them out of the list rather than render empty blocks.
    if (end > start) blocks.push({ page: i + 1, start, end })
  }
  return blocks
}

/** Page (1-based) containing an offset, or null when there are no pages. */
export function pageAt(offset: number, pageStarts: number[]): number | null {
  if (pageStarts.length === 0) return null
  let page = 1
  for (let i = 0; i < pageStarts.length; i++) if (pageStarts[i] <= offset) page = i + 1
  return page
}

/**
 * Sentences of an answer with the citation labels each one uses. Used by X-Ray so clicking a sentence highlights the
 * passages that support it. A label in square brackets right after a sentence belongs to that sentence.
 */
export interface AnswerSentence {
  text: string
  labels: string[]
}

export function answerSentences(answer: string): AnswerSentence[] {
  const out: AnswerSentence[] = []
  // A sentence ends at . ! or ? (optionally followed by citation brackets) and then whitespace or the end of the text.
  const re = /[^.!?\n]+(?:[.!?]+(?:\s*\[c\d+(?:\s*,\s*c\d+)*\])*)?(?:\s+|$)/g
  for (const m of answer.matchAll(re)) {
    const text = m[0]
    if (!text.trim()) continue
    const labels = new Set<string>()
    for (const group of text.matchAll(/\[(c\d+(?:\s*,\s*c\d+)*)\]/g)) for (const l of group[1].split(/\s*,\s*/)) labels.add(l)
    out.push({ text, labels: [...labels] })
  }
  return out
}
