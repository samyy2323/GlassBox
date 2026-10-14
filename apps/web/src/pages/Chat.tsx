import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { UploadCloud } from 'lucide-react'
import { useLocation, useParams } from 'react-router-dom'
import { AssistantMessage, UserMessage } from '../components/MessageView'
import { Composer, type ComposerHandle } from '../components/Composer'
import { Logo } from '../components/Logo'
import { SourceDrawer, type SourceTarget } from '../components/SourceDrawer'
import { api, ApiError, streamChat, type Citation } from '../lib/api'
import { reduce, turnFromTrace } from '../lib/chatState'
import { queryKeys } from '../lib/queryKeys'
import { SUPPORTED_SUMMARY } from '../lib/formats'
import { suggestionsFor } from '../lib/suggestions'
import { useUploads } from '../lib/uploads'
import { useWorkspace } from '../lib/workspace'

export default function Chat() {
  const { traceId } = useParams()
  const location = useLocation()
  const qc = useQueryClient()
  const { current } = useWorkspace()
  const { enqueue } = useUploads()
  const [turns, dispatch] = useReducer(reduce, [])
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [restore, setRestore] = useState<string | undefined>()
  const [source, setSource] = useState<{ target: SourceTarget; label: string; traceId?: string } | null>(null)
  const abort = useRef<AbortController | null>(null)
  const nextId = useRef(0)
  const composer = useRef<ComposerHandle>(null)
  const scroller = useRef<HTMLDivElement>(null)
  const picker = useRef<HTMLInputElement>(null)

  const docs = useQuery({ queryKey: queryKeys.documents(current?.id), queryFn: () => api.documents(), enabled: !!current })
  const ready = useMemo(() => docs.data?.items.filter((d) => d.status === 'READY') ?? [], [docs.data])

  // Reopening a past answer from the history list.
  const past = useQuery({ queryKey: queryKeys.trace(traceId ?? ''), queryFn: () => api.trace(traceId!), enabled: !!traceId })
  useEffect(() => {
    if (traceId && past.data) dispatch({ type: 'load', turns: [turnFromTrace(past.data, nextId.current++)] })
  }, [traceId, past.data])

  // "New chat" (or leaving a past answer) starts from a clean page and stops anything still generating.
  useEffect(() => {
    if (traceId) return
    abort.current?.abort()
    dispatch({ type: 'reset' })
    setSource(null)
    setNotice(null)
    setTimeout(() => composer.current?.focus(), 0)
  }, [traceId, location.state])

  // Leaving the page stops generation, which frees the server's single answer slot.
  useEffect(() => () => abort.current?.abort(), [])

  // Follow the answer as it grows.
  useEffect(() => {
    const el = scroller.current
    if (el) el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
  }, [turns])

  // "/" jumps to the message box, like in most chat apps.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement
      if (e.key === '/' && !e.ctrlKey && !e.metaKey && !['INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName)) {
        e.preventDefault()
        composer.current?.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const ask = useCallback(
    async (question: string) => {
      setNotice(null)
      setRestore(undefined)
      setBusy(true)
      const id = nextId.current++
      const ctrl = new AbortController()
      abort.current = ctrl
      dispatch({ type: 'start', id, question })
      try {
        await streamChat(question, (ev) => dispatch({ type: 'event', id, event: ev.event, data: ev.data ? JSON.parse(ev.data) : {} }), ctrl.signal)
        dispatch({ type: 'ended', id })
      } catch (err) {
        if (ctrl.signal.aborted) {
          dispatch({ type: 'abort', id })
        } else if (err instanceof ApiError && err.status === 429) {
          // Refused before anything streamed: take the empty turn back and give the question back to the user.
          dispatch({ type: 'drop', id })
          setRestore(question)
          setNotice(`${err.message}${err.retryAfterSeconds ? ` Try again in ${err.retryAfterSeconds} s.` : ''}`)
        } else {
          dispatch({ type: 'fail', id, message: err instanceof Error ? err.message : 'Something went wrong' })
        }
      } finally {
        setBusy(false)
        abort.current = null
        // the answer now belongs in the sidebar history and the X-Ray list
        void qc.invalidateQueries({ queryKey: queryKeys.recentTraces(current?.id) })
        void qc.invalidateQueries({ queryKey: queryKeys.traceList(current?.id) })
      }
    },
    [qc, current?.id],
  )

  function regenerate() {
    const last = turns[turns.length - 1]
    if (!last || busy) return
    dispatch({ type: 'drop', id: last.id })
    void ask(last.question)
  }

  function openSource(c: Citation, turnTraceId?: string) {
    setSource({
      target: { documentId: c.documentId, title: c.title, badge: `Source ${c.label.slice(1)}`, subtitle: c.page != null ? `Page ${c.page}` : 'Highlighted passage', start: c.charStart, end: c.charEnd },
      label: c.label,
      traceId: turnTraceId,
    })
  }

  if (!current) return null
  const empty = turns.length === 0 && !(traceId && past.isLoading)
  const suggestions = suggestionsFor(ready.map((d) => d.title))

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div ref={scroller} className="min-h-0 flex-1 overflow-y-auto" aria-label="Conversation">
        <div className="mx-auto flex min-h-full w-full max-w-[46rem] flex-col px-4 py-8 sm:px-6">
          {traceId && past.isLoading && (
            <div className="space-y-4" aria-label="Loading conversation">
              <div className="skeleton ml-auto h-12 w-2/3" />
              <div className="skeleton h-28 w-full" />
            </div>
          )}
          {traceId && past.isError && <p role="alert" className="glass rounded-2xl p-4 text-sm">This conversation could not be found. It may have expired or belong to another workspace.</p>}

          {empty && !(traceId && past.isError) && (
            <div className="m-auto flex w-full animate-fade-up flex-col items-center py-8 text-center">
              <Logo size={56} pulse />
              <h1 className="mt-7 text-[2rem] font-semibold leading-[1.15] sm:text-[2.6rem]"><span className="text-gradient">How can I help with<br className="sm:hidden" /> your documents?</span></h1>
              <p className="mt-3.5 max-w-md text-[0.92rem] leading-relaxed text-muted">
                {docs.isSuccess && ready.length > 0
                  ? 'I search what you have uploaded, cite every source, and tell you plainly when the answer is not there.'
                  : 'Add documents, then ask questions. Every answer cites its sources.'}
              </p>
              {docs.isSuccess && ready.length > 0 && (
                <span className="chip mt-4"><span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />{ready.length} document{ready.length === 1 ? '' : 's'} ready</span>
              )}

              {docs.isSuccess && ready.length === 0 ? (
                <button type="button" onClick={() => picker.current?.click()} className="glass glass-interactive mt-9 flex w-full max-w-lg flex-col items-center gap-2 rounded-3xl border-2 !border-dashed px-8 py-11">
                  <UploadCloud className="h-9 w-9" strokeWidth={1.6} />
                  <span className="font-semibold">Add your first documents</span>
                  <span className="text-xs text-muted">Click to choose, or drop files anywhere</span>
                  <span className="mt-1 max-w-sm text-[11px] leading-relaxed text-muted">{SUPPORTED_SUMMARY}</span>
                </button>
              ) : (
                <ul className="mt-9 grid w-full gap-3 sm:grid-cols-2">
                  {suggestions.map((s, i) => (
                    <li key={s.prompt} className="animate-fade-up" style={{ animationDelay: `${100 + i * 70}ms` }}>
                      <button type="button" disabled={busy} onClick={() => void ask(s.prompt)} className="glass glass-interactive flex h-full w-full flex-col items-start gap-2 rounded-2xl p-4 text-left">
                        <span className="grid h-8 w-8 place-items-center rounded-lg border border-[var(--line)] bg-fg/[0.06]"><s.icon className="h-4 w-4" /></span>
                        <span className="eyebrow">{s.kind}</span>
                        <span className="text-[0.88rem] font-medium leading-snug">{s.title}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              <p className="mt-7 flex flex-wrap items-center justify-center gap-x-2 gap-y-1 text-xs text-muted">
                Press <span className="kbd">/</span> to type <span className="mx-1 opacity-40">·</span> <span className="kbd">Ctrl K</span> for commands <span className="mx-1 opacity-40">·</span> drop files anywhere to add them
              </p>
            </div>
          )}

          <div className="space-y-9">
            {turns.map((t, i) => (
              <div key={t.id} className="space-y-6">
                <UserMessage text={t.question} />
                <AssistantMessage
                  turn={t}
                  isLast={i === turns.length - 1 && !busy}
                  activeLabel={source?.label}
                  onOpenSource={(c) => openSource(c, t.traceId)}
                  onRegenerate={regenerate}
                  onUpload={() => picker.current?.click()}
                />
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="px-4 pb-4 pt-2 sm:px-6">
        {notice && <p role="alert" className="glass mx-auto mb-2 max-w-[46rem] rounded-2xl px-4 py-2.5 text-sm">{notice}</p>}
        <Composer ref={composer} busy={busy} restore={restore} onSend={(q) => void ask(q)} onStop={() => abort.current?.abort()} onAttach={enqueue} />
        <p className="mx-auto mt-2.5 max-w-[46rem] text-center text-[11px] text-muted">Answers come only from your documents. Check the cited sources.</p>
      </div>

      <input ref={picker} type="file" multiple className="sr-only" tabIndex={-1} aria-hidden onChange={(e) => { if (e.target.files?.length) enqueue([...e.target.files]); e.target.value = '' }} />
      <SourceDrawer source={source?.target ?? null} traceId={source?.traceId} onClose={() => setSource(null)} />
    </div>
  )
}
