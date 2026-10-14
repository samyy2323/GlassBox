import { useEffect, useRef } from 'react'
import { ExternalLink, X } from 'lucide-react'
import { Link } from 'react-router-dom'
import { DocumentViewer } from './DocumentViewer'

export interface SourceTarget {
  documentId: string
  title: string
  subtitle?: string
  /** Badge above the title, e.g. "Source 2". */
  badge?: string
  /** Character range to highlight; omit to just open the document. */
  start?: number
  end?: number
}

interface Props {
  source: SourceTarget | null
  traceId?: string
  onClose: () => void
}

/** Slide-over that shows a document, with an optional passage exactly highlighted. Esc or a click outside closes it. */
export function SourceDrawer({ source, traceId, onClose }: Props) {
  const close = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!source) return
    close.current?.focus()
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [source, onClose])

  if (!source) return null
  const highlights = source.start != null && source.end != null ? [{ start: source.start, end: source.end, kind: 'primary' as const, label: source.badge }] : []
  return (
    <div className="fixed inset-0 z-50 flex justify-end" role="dialog" aria-modal="true" aria-label={source.title}>
      <button type="button" aria-label="Close" className="absolute inset-0 animate-fade-in bg-black/40 backdrop-blur-sm" onClick={onClose} tabIndex={-1} />
      <aside className="glass-strong relative flex h-full w-[min(34rem,100vw)] animate-slide-in-right flex-col rounded-l-3xl">
        <header className="flex items-start gap-3 border-b border-[var(--line)] px-5 py-4">
          <div className="min-w-0 flex-1">
            {source.badge && <p className="chip mb-1.5 w-fit">{source.badge}</p>}
            <h2 className="truncate text-base font-semibold">{source.title}</h2>
            {source.subtitle && <p className="text-xs text-muted">{source.subtitle}</p>}
          </div>
          <button ref={close} className="btn-ghost !p-2" onClick={onClose} aria-label="Close">
            <X className="h-5 w-5" />
          </button>
        </header>
        <div className="min-h-0 flex-1 p-4">
          <DocumentViewer documentId={source.documentId} highlights={highlights} className="h-full" />
        </div>
        {traceId && (
          <footer className="border-t border-[var(--line)] px-5 py-3">
            <Link to={`/xray/${traceId}`} className="btn-ghost" onClick={onClose}>
              <ExternalLink className="h-4 w-4" /> See how this answer was made
            </Link>
          </footer>
        )}
      </aside>
    </div>
  )
}
