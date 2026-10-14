import { AlertCircle, Check, CopyCheck, Loader2, X } from 'lucide-react'
import { useUploads } from '../lib/uploads'

/** Floating progress for uploads, visible on every page. Failures say why. */
export function UploadToast() {
  const { items, dismiss, clearFinished } = useUploads()
  if (items.length === 0) return null
  const active = items.filter((i) => i.state === 'queued' || i.state === 'uploading').length

  return (
    <section aria-label="Uploads" className="glass-strong fixed bottom-4 right-4 z-40 w-[min(22rem,calc(100vw-2rem))] animate-fade-up overflow-hidden rounded-2xl">
      <header className="flex items-center justify-between px-4 py-2.5 text-sm font-semibold">
        <span>{active > 0 ? `Uploading ${active} file${active === 1 ? '' : 's'}…` : 'Uploads finished'}</span>
        {active === 0 && (
          <button className="btn-ghost !px-2 !py-1" onClick={clearFinished} aria-label="Clear finished uploads">
            Clear
          </button>
        )}
      </header>
      <ul className="max-h-60 divide-y divide-[var(--line)] overflow-y-auto border-t border-[var(--line)]" aria-live="polite">
        {items.map((i) => (
          <li key={i.key} className="flex items-center gap-3 px-4 py-2 text-sm">
            <span className="shrink-0" aria-hidden>
              {i.state === 'uploading' || i.state === 'queued' ? (
                <Loader2 className={`h-4 w-4 ${i.state === 'uploading' ? 'animate-spin text-fg' : 'text-muted'}`} />
              ) : i.state === 'done' ? (
                <Check className="h-4 w-4 text-fg" />
              ) : i.state === 'duplicate' ? (
                <CopyCheck className="h-4 w-4 text-muted" />
              ) : (
                <AlertCircle className="h-4 w-4 text-red-500" />
              )}
            </span>
            <span className="min-w-0 flex-1">
              <span className="block truncate font-medium">{i.name}</span>
              {i.message && <span className={`block truncate text-xs ${i.state === 'error' ? 'text-red-500' : 'text-muted'}`}>{i.message}</span>}
            </span>
            {i.state !== 'uploading' && i.state !== 'queued' && (
              <button className="text-muted hover:text-fg" onClick={() => dismiss(i.key)} aria-label={`Dismiss ${i.name}`}>
                <X className="h-4 w-4" />
              </button>
            )}
          </li>
        ))}
      </ul>
    </section>
  )
}
