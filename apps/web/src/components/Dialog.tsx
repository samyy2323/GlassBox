import { useEffect, type ReactNode } from 'react'
import { X } from 'lucide-react'

/** A centred panel over a dimmed page. Escape and a click outside close it. */
export function Dialog({ title, onClose, children, wide = false }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-50 grid place-items-center p-4" role="dialog" aria-modal="true" aria-label={title}>
      <button type="button" aria-label="Close" tabIndex={-1} className="absolute inset-0 animate-fade-in bg-black/60" onClick={onClose} />
      <div className={`glass-strong relative flex max-h-[90dvh] w-full animate-pop-in flex-col overflow-hidden rounded-3xl ${wide ? 'max-w-5xl' : 'max-w-lg'}`} style={{ boxShadow: 'var(--shadow-float)' }}>
        <header className="flex items-center justify-between gap-3 border-b border-[var(--line)] px-5 py-4">
          <h2 className="text-base font-semibold">{title}</h2>
          <button type="button" className="btn-ghost !p-1.5" onClick={onClose} aria-label="Close dialog"><X className="h-4 w-4" /></button>
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto p-5">{children}</div>
      </div>
    </div>
  )
}
