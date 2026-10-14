// Turns a text selection in the document viewer into character offsets of the stored document text, which is what gold
// evidence is made of. Every piece of text in the viewer sits in an element with data-start (its offset in the document), so
// an end of a selection is (that start, offset inside the element's text).

export interface SelectionEnd {
  /** data-start of the element holding the text node. */
  start: number
  /** Offset inside that element's text. */
  offset: number
}

export interface Range {
  start: number
  end: number
}

/** Both ends of a selection, in either direction, as one range of the document text. Null when it selects nothing. */
export function rangeFromEnds(a: SelectionEnd, b: SelectionEnd, textLength: number): Range | null {
  const x = a.start + a.offset
  const y = b.start + b.offset
  const start = Math.max(0, Math.min(x, y))
  const end = Math.min(textLength, Math.max(x, y))
  return end > start ? { start, end } : null
}

/** Drops whitespace from both ends of the range, so evidence does not start or end with blanks. Null if only blanks remain. */
export function trimRange(text: string, r: Range): Range | null {
  let { start, end } = r
  while (start < end && /\s/.test(text[start])) start++
  while (end > start && /\s/.test(text[end - 1])) end--
  return end > start ? { start, end } : null
}

function endOf(node: Node | null, offset: number): SelectionEnd | null {
  let el: Node | null = node
  while (el && !(el instanceof HTMLElement && el.dataset.start != null)) el = el.parentNode
  if (!(el instanceof HTMLElement)) return null
  // when the boundary sits on the element itself (not in its text node), offset counts child nodes: 0 is the start, otherwise the end
  const inside = node === el ? (offset === 0 ? 0 : (el.textContent ?? '').length) : offset
  return { start: Number(el.dataset.start), offset: inside }
}

/** The current browser selection inside {@code root} as document offsets, or null. */
export function readSelection(root: HTMLElement, text: string): Range | null {
  const sel = window.getSelection()
  if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null
  const r = sel.getRangeAt(0)
  if (!root.contains(r.startContainer) || !root.contains(r.endContainer)) return null
  const a = endOf(r.startContainer, r.startOffset)
  const b = endOf(r.endContainer, r.endOffset)
  if (!a || !b) return null
  const range = rangeFromEnds(a, b, text.length)
  return range ? trimRange(text, range) : null
}
