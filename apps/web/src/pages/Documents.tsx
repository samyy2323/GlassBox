import { useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Search, Trash2, UploadCloud } from 'lucide-react'
import { Link } from 'react-router-dom'
import { SourceDrawer, type SourceTarget } from '../components/SourceDrawer'
import { StatusChip } from '../components/StatusChip'
import { api, type DocumentDto } from '../lib/api'
import { queryKeys } from '../lib/queryKeys'
import { formatOf, NOT_SUPPORTED_NOTE, SUPPORTED_SUMMARY } from '../lib/formats'
import { useLiveDocuments } from '../lib/useLiveDocuments'
import { useUploads } from '../lib/uploads'
import { useWorkspace } from '../lib/workspace'

function formatSize(bytes: number) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function DocCard({ doc, index, canEdit, onOpen, onDelete, busy }: { doc: DocumentDto; index: number; canEdit: boolean; onOpen: () => void; onDelete: () => void; busy: boolean }) {
  const f = formatOf(doc.sourceType)
  const readable = doc.status === 'READY'
  return (
    <li className="animate-fade-up" style={{ animationDelay: `${Math.min(index, 12) * 40}ms` }}>
      <div className="glass glass-interactive group relative flex h-full flex-col gap-3 rounded-2xl p-4">
        <div className="flex items-start gap-3">
          <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl border border-[var(--line)] bg-fg/[0.06] text-fg">
            <f.icon className="h-5 w-5" />
          </span>
          <div className="min-w-0 flex-1">
            <button type="button" onClick={onOpen} disabled={!readable} className="block w-full truncate text-left text-[0.95rem] font-semibold hover:underline disabled:no-underline" title={doc.title}>
              {doc.title}
            </button>
            <p className="text-xs text-muted">{f.label} · {formatSize(doc.sizeBytes)} · v{doc.version}{doc.version > 1 && <> · <Link to={`/documents/${doc.id}/changes`} className="font-medium text-accent underline-offset-4 hover:underline">changes</Link></>}</p>
          </div>
          {canEdit && (
            <button
              className="btn-ghost !p-2 opacity-0 transition focus:opacity-100 group-hover:opacity-100 [@media(hover:none)]:opacity-100"
              disabled={busy}
              aria-label={`Delete ${doc.title}`}
              onClick={() => { if (window.confirm(`Delete "${doc.title}" and everything derived from it?`)) onDelete() }}
            >
              <Trash2 className="h-4 w-4 text-red-500" />
            </button>
          )}
        </div>
        <div className="mt-auto flex items-center justify-between gap-2">
          <StatusChip status={doc.status} title={doc.error ?? undefined} />
          {readable && <button type="button" onClick={onOpen} className="text-xs font-medium underline-offset-4 hover:underline">Preview</button>}
        </div>
        {doc.error && <p className="text-xs leading-relaxed text-muted">{doc.error}</p>}
      </div>
    </li>
  )
}

export default function Documents() {
  const { current, loading } = useWorkspace()
  const qc = useQueryClient()
  const { enqueue } = useUploads()
  const canEdit = current != null && current.role !== 'VIEWER'
  const [filter, setFilter] = useState('')
  const [preview, setPreview] = useState<SourceTarget | null>(null)
  const picker = useRef<HTMLInputElement>(null)

  const key = queryKeys.documents(current?.id)
  const docs = useQuery({
    queryKey: key,
    queryFn: () => api.documents(),
    enabled: !!current,
    // Safety net for when the live stream cannot connect.
    refetchInterval: (q) => (q.state.data?.items.some((d) => d.status === 'UPLOADED' || d.status === 'PROCESSING') ? 3000 : false),
  })
  useLiveDocuments(current?.id, () => void qc.invalidateQueries({ queryKey: key }))

  const remove = useMutation({ mutationFn: (id: string) => api.deleteDocument(id), onSuccess: () => qc.invalidateQueries({ queryKey: key }) })

  const items = useMemo(() => docs.data?.items ?? [], [docs.data])
  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase()
    return q ? items.filter((d) => d.title.toLowerCase().includes(q) || formatOf(d.sourceType).label.toLowerCase().includes(q)) : items
  }, [items, filter])
  const count = (...s: string[]) => items.filter((d) => s.includes(d.status)).length

  if (loading || docs.isLoading) {
    return (
      <div className="mx-auto w-full max-w-6xl space-y-4 p-6" aria-label="Loading documents">
        <div className="skeleton h-10 w-60" />
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">{[0, 1, 2].map((i) => <div key={i} className="skeleton h-32" />)}</div>
      </div>
    )
  }
  if (!current) return <p className="p-6 text-muted">You are not a member of any workspace yet.</p>
  if (docs.isError) return <p role="alert" className="p-6 text-red-500">Could not load documents. {docs.error instanceof Error ? docs.error.message : ''}</p>

  const ready = count('READY')
  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto w-full max-w-6xl space-y-6 p-4 sm:p-6">
        <header className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="text-[1.65rem] font-semibold tracking-tight">Library</h1>
            <p className="mt-1 text-sm text-muted">Everything your answers are built from.</p>
          </div>
          {canEdit && (
            <button className="btn-primary" onClick={() => picker.current?.click()}>
              <UploadCloud className="h-[18px] w-[18px]" /> Upload documents
            </button>
          )}
        </header>
        <input ref={picker} type="file" multiple className="sr-only" tabIndex={-1} aria-hidden onChange={(e) => { if (e.target.files?.length) enqueue([...e.target.files]); e.target.value = '' }} />

        {items.length > 0 && (
          <div className="flex flex-wrap items-center gap-3">
            <label className="relative min-w-[14rem] flex-1 sm:max-w-sm">
              <span className="sr-only">Search documents</span>
              <Search className="pointer-events-none absolute left-3.5 top-1/2 z-10 h-4 w-4 -translate-y-1/2 text-muted" />
              <input className="input-glass !pl-10" placeholder="Search documents…" value={filter} onChange={(e) => setFilter(e.target.value)} />
            </label>
            <div className="flex flex-wrap gap-2" aria-label="Summary">
              <span className="chip"><span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />{ready} ready</span>
              {count('UPLOADED', 'PROCESSING') > 0 && <span className="chip"><span className="h-1.5 w-1.5 animate-pulse rounded-full bg-amber-400" />{count('UPLOADED', 'PROCESSING')} in progress</span>}
              {count('FAILED', 'FAILED_UNSUPPORTED') > 0 && <span className="chip"><span className="h-1.5 w-1.5 rounded-full bg-red-500" />{count('FAILED', 'FAILED_UNSUPPORTED')} need attention</span>}
            </div>
            {ready > 0 && <Link to="/chat" className="ml-auto text-sm font-medium underline-offset-4 hover:underline">Ask about {ready === 1 ? 'this document' : `these ${ready}`} →</Link>}
          </div>
        )}

        {items.length === 0 ? (
          <div className="glass flex flex-col items-center gap-3 rounded-3xl border-2 !border-dashed px-6 py-16 text-center animate-fade-up">
            <span className="grid h-16 w-16 place-items-center rounded-2xl bg-fg text-canvas shadow-[0_8px_30px_rgb(var(--fg-rgb)/0.25)]"><UploadCloud className="h-8 w-8" /></span>
            <h2 className="text-lg font-semibold">Your library is empty</h2>
            <p className="max-w-md text-sm text-muted">{canEdit ? 'Drop files anywhere on this page, or use the button above. They are read in the background, so you can keep working.' : 'You have read-only access to this workspace, so uploads are disabled.'}</p>
            <p className="max-w-lg text-xs text-muted">{SUPPORTED_SUMMARY}</p>
          </div>
        ) : shown.length === 0 ? (
          <p className="glass rounded-2xl p-6 text-center text-sm text-muted">No documents match “{filter}”.</p>
        ) : (
          <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {shown.map((d, i) => (
              <DocCard
                key={d.id}
                doc={d}
                index={i}
                canEdit={canEdit}
                busy={remove.isPending}
                onDelete={() => remove.mutate(d.id)}
                onOpen={() => setPreview({ documentId: d.id, title: d.title, subtitle: `${formatOf(d.sourceType).label} · version ${d.version}` })}
              />
            ))}
          </ul>
        )}

        {items.length > 0 && <p className="text-center text-xs text-muted">{SUPPORTED_SUMMARY}. {NOT_SUPPORTED_NOTE}</p>}
        {remove.isError && <p role="alert" className="text-sm text-red-500">Could not delete: {remove.error instanceof Error ? remove.error.message : 'error'}</p>}
      </div>
      <SourceDrawer source={preview} onClose={() => setPreview(null)} />
    </div>
  )
}
