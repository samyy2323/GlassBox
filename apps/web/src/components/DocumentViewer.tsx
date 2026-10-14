import { useEffect, useMemo, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'
import { pageBlocks, segmentText, type Highlight, type HighlightKind } from '../lib/highlight'

const MARK: Record<HighlightKind, string> = {
  primary: 'bg-amber-300 text-slate-900 dark:bg-amber-400',
  secondary: 'bg-indigo-200 text-slate-900 dark:bg-indigo-400/70',
  candidate: 'bg-slate-200 dark:bg-slate-600/60',
}

interface Props {
  documentId: string
  highlights: Highlight[]
  /** Increment to scroll to the primary highlight again (e.g. when the same passage is clicked twice). */
  scrollKey?: number
  className?: string
}

/**
 * The extracted text of a document with page markers, exactly as retrieval saw it. Highlights are character ranges of that
 * text, so a cited passage is marked precisely. (A rendered PDF page would need its text layer matched to ours; see ADR 0004.)
 * Every highlighted piece carries data-start so a selection can be turned back into character offsets.
 */
export function DocumentViewer({ documentId, highlights, scrollKey, className }: Props) {
  const content = useQuery({ queryKey: ['document-content', documentId], queryFn: () => api.documentContent(documentId), staleTime: 5 * 60_000 })
  const root = useRef<HTMLDivElement>(null)

  const text = content.data?.text
  const blocks = useMemo(() => (text == null ? [] : pageBlocks(text.length, content.data?.pageStarts ?? [])), [text, content.data?.pageStarts])

  const firstPrimary = highlights.find((h) => h.kind === 'primary')?.start ?? highlights[0]?.start
  useEffect(() => {
    if (!root.current || firstPrimary == null) return
    const marks = [...root.current.querySelectorAll<HTMLElement>('mark[data-kind="primary"]')]
    const target = marks[0] ?? root.current.querySelector<HTMLElement>('mark')
    target?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [firstPrimary, scrollKey, documentId, text])

  if (content.isLoading) return <div className="h-48 animate-pulse rounded-md bg-slate-200 dark:bg-slate-800" aria-label="Loading document" />
  if (content.isError) return <p role="alert" className="text-sm text-red-600">This document is no longer available.</p>
  if (text == null) return <p className="text-sm text-slate-500">This document has not finished processing yet.</p>

  return (
    <div ref={root} className={`overflow-y-auto rounded-md border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900 ${className ?? ''}`}>
      <h3 className="mb-2 text-xs font-semibold uppercase text-slate-500">{content.data?.document.title}</h3>
      {blocks.map((b) => (
        <section key={b.start} aria-label={b.page ? `Page ${b.page}` : undefined}>
          {b.page != null && (
            <div className="my-3 flex items-center gap-2 text-xs font-medium text-slate-400">
              <span className="h-px flex-1 bg-slate-200 dark:bg-slate-700" />
              Page {b.page}
              <span className="h-px flex-1 bg-slate-200 dark:bg-slate-700" />
            </div>
          )}
          <p className="whitespace-pre-wrap text-sm leading-relaxed">
            {segmentText(text, highlights, b.start, b.end).map((s) =>
              s.kind ? (
                <mark key={s.start} data-start={s.start} data-kind={s.kind} title={s.label} className={`rounded px-0.5 ${MARK[s.kind]}`}>
                  {s.text}
                </mark>
              ) : (
                <span key={s.start} data-start={s.start}>{s.text}</span>
              ),
            )}
          </p>
        </section>
      ))}
    </div>
  )
}
