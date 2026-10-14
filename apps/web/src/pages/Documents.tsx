import { useRef, useState, type DragEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api, ApiError, type DocStatus, type DocumentDto } from '../lib/api'
import { useLiveDocuments } from '../lib/useLiveDocuments'
import { useWorkspace } from '../lib/workspace'

const MAX_BYTES = 25 * 1024 * 1024
const ACCEPT = '.pdf,.docx,.md,.markdown,.html,.htm'

const STATUS: Record<DocStatus, { label: string; cls: string }> = {
  UPLOADED: { label: 'Queued', cls: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300' },
  PROCESSING: { label: 'Processing', cls: 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300 animate-pulse' },
  READY: { label: 'Ready', cls: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300' },
  FAILED: { label: 'Failed', cls: 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300' },
  FAILED_UNSUPPORTED: { label: 'Unsupported', cls: 'bg-orange-100 text-orange-800 dark:bg-orange-900/40 dark:text-orange-300' },
}

function StatusChip({ doc }: { doc: DocumentDto }) {
  const s = STATUS[doc.status]
  return (
    <span className={`inline-block rounded-full px-2.5 py-0.5 text-xs font-medium ${s.cls}`} title={doc.error ?? undefined}>
      {s.label}
    </span>
  )
}

interface UploadItem {
  key: number
  name: string
  state: 'uploading' | 'done' | 'duplicate' | 'error'
  message?: string
}

function formatSize(bytes: number) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

export default function Documents() {
  const { current, loading } = useWorkspace()
  const qc = useQueryClient()
  const canEdit = current != null && current.role !== 'VIEWER'
  const [uploads, setUploads] = useState<UploadItem[]>([])
  const [dragging, setDragging] = useState(false)
  const input = useRef<HTMLInputElement>(null)
  const nextKey = useRef(0)

  const key = ['documents', current?.id]
  const docs = useQuery({
    queryKey: key,
    queryFn: () => api.documents(),
    enabled: !!current,
    // Safety net for when the live stream cannot connect.
    refetchInterval: (q) => (q.state.data?.items.some((d) => d.status === 'UPLOADED' || d.status === 'PROCESSING') ? 3000 : false),
  })
  useLiveDocuments(current?.id, () => void qc.invalidateQueries({ queryKey: key }))

  const remove = useMutation({
    mutationFn: (id: string) => api.deleteDocument(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: key }),
  })

  function patch(k: number, p: Partial<UploadItem>) {
    setUploads((u) => u.map((x) => (x.key === k ? { ...x, ...p } : x)))
  }

  async function uploadAll(files: File[]) {
    const items = files.map((f) => ({ key: nextKey.current++, name: f.name, state: 'uploading' as const }))
    setUploads((u) => [...items, ...u].slice(0, 20))
    // One at a time: the server ingests asynchronously, so there is nothing to gain from parallel uploads.
    for (const [i, file] of files.entries()) {
      const k = items[i].key
      if (file.size > MAX_BYTES) {
        patch(k, { state: 'error', message: 'Larger than 25 MB' })
        continue
      }
      try {
        const r = await api.upload(file)
        patch(k, r.duplicate ? { state: 'duplicate', message: 'Already in this workspace' } : { state: 'done' })
      } catch (e) {
        patch(k, { state: 'error', message: e instanceof ApiError ? e.message : 'Upload failed' })
      }
      void qc.invalidateQueries({ queryKey: key })
    }
  }

  function onDrop(e: DragEvent) {
    e.preventDefault()
    setDragging(false)
    if (canEdit && e.dataTransfer.files.length) void uploadAll([...e.dataTransfer.files])
  }

  if (loading || docs.isLoading) return <div className="h-40 animate-pulse rounded-lg bg-slate-200 dark:bg-slate-800" aria-label="Loading documents" />
  if (!current) return <p className="text-slate-500">You are not a member of any workspace yet.</p>
  if (docs.isError) return <p role="alert" className="text-red-600">Could not load documents. {docs.error instanceof Error ? docs.error.message : ''}</p>

  const items = docs.data?.items ?? []
  const ready = items.filter((d) => d.status === 'READY').length

  return (
    <section className="space-y-6">
      <div className="flex items-baseline justify-between">
        <h1 className="text-lg font-semibold">Documents</h1>
        {ready > 0 && (
          <Link to="/chat" className="text-sm text-indigo-600 hover:underline">
            Ask a question about {ready === 1 ? 'this document' : `these ${ready} documents`} →
          </Link>
        )}
      </div>

      {canEdit ? (
        <label
          onDragOver={(e) => { e.preventDefault(); setDragging(true) }}
          onDragLeave={() => setDragging(false)}
          onDrop={onDrop}
          className={`flex cursor-pointer flex-col items-center justify-center rounded-lg border-2 border-dashed px-6 py-10 text-center text-sm focus-within:outline-2 focus-within:outline-indigo-500 ${
            dragging ? 'border-indigo-500 bg-indigo-50 dark:bg-indigo-950/30' : 'border-slate-300 dark:border-slate-700'
          }`}
        >
          <span className="font-medium">Drop files here or click to choose</span>
          <span className="mt-1 text-slate-500">PDF, Word (.docx), Markdown or HTML · up to 25 MB each</span>
          <input
            ref={input}
            type="file"
            multiple
            accept={ACCEPT}
            className="sr-only"
            onChange={(e) => {
              if (e.target.files?.length) void uploadAll([...e.target.files])
              e.target.value = ''
            }}
          />
        </label>
      ) : (
        <p className="rounded-md bg-slate-100 px-3 py-2 text-sm text-slate-600 dark:bg-slate-800 dark:text-slate-300">
          You have read-only access to this workspace, so uploads are disabled.
        </p>
      )}

      {uploads.length > 0 && (
        <ul className="space-y-1 text-sm" aria-live="polite">
          {uploads.map((u) => (
            <li key={u.key} className="flex gap-2">
              <span className="truncate font-medium">{u.name}</span>
              <span className={u.state === 'error' ? 'text-red-600' : 'text-slate-500'}>
                {u.state === 'uploading' ? 'uploading…' : u.state === 'done' ? 'uploaded' : u.message}
              </span>
            </li>
          ))}
        </ul>
      )}

      {items.length === 0 ? (
        <div className="card text-center text-sm text-slate-500">
          <p>No documents yet.</p>
          {canEdit && <p className="mt-1">Upload a few files above, then ask questions about them in Chat.</p>}
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-800">
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Documents in this workspace</caption>
            <thead className="bg-slate-100 text-xs uppercase text-slate-500 dark:bg-slate-900">
              <tr>
                <th scope="col" className="px-4 py-2">Title</th>
                <th scope="col" className="px-4 py-2">Type</th>
                <th scope="col" className="px-4 py-2">Status</th>
                <th scope="col" className="px-4 py-2">Size</th>
                <th scope="col" className="px-4 py-2">Version</th>
                <th scope="col" className="px-4 py-2"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
              {items.map((d) => (
                <tr key={d.id}>
                  <td className="px-4 py-2 font-medium">
                    {d.title}
                    {d.error && <div className="text-xs font-normal text-slate-500">{d.error}</div>}
                  </td>
                  <td className="px-4 py-2 text-slate-500">{d.sourceType}</td>
                  <td className="px-4 py-2"><StatusChip doc={d} /></td>
                  <td className="px-4 py-2 text-slate-500">{formatSize(d.sizeBytes)}</td>
                  <td className="px-4 py-2 text-slate-500">v{d.version}</td>
                  <td className="px-4 py-2 text-right">
                    {canEdit && (
                      <button
                        className="text-red-600 hover:underline disabled:opacity-50"
                        disabled={remove.isPending}
                        onClick={() => { if (window.confirm(`Delete "${d.title}" and everything derived from it?`)) remove.mutate(d.id) }}
                      >
                        Delete
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {remove.isError && <p role="alert" className="text-sm text-red-600">Could not delete: {remove.error instanceof Error ? remove.error.message : 'error'}</p>}
    </section>
  )
}
