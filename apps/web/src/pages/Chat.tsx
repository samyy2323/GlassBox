import { Fragment, useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api, ApiError, streamChat, type Citation, type TraceSpan } from '../lib/api'
import { useWorkspace } from '../lib/workspace'

type TurnStatus = 'streaming' | 'done' | 'error' | 'stopped'

interface Turn {
  id: number
  question: string
  answer: string
  status: TurnStatus
  citations: Citation[]
  unknownLabels: string[]
  spans: TraceSpan[]
  error?: string
  sanitized?: boolean
  abstained?: boolean
  model?: string
  latencyMs?: number
  traceId?: string
}

const LABELS = /(\[c\d+(?:\s*,\s*c\d+)*\])/g

/** Answer text as plain text nodes (never HTML), with [c1] markers turned into buttons that open the source. */
function AnswerText({ text, citations, unknown, onPick, active }: {
  text: string
  citations: Citation[]
  unknown: string[]
  onPick: (label: string) => void
  active?: string
}) {
  return (
    <p className="whitespace-pre-wrap leading-relaxed">
      {text.split(LABELS).map((part, i) => {
        if (!/^\[c\d/.test(part)) return <Fragment key={i}>{part}</Fragment>
        return (
          <Fragment key={i}>
            {part.slice(1, -1).split(/\s*,\s*/).map((label) => {
              const known = citations.some((c) => c.label === label)
              return known ? (
                <button
                  key={label}
                  type="button"
                  onClick={() => onPick(label)}
                  aria-pressed={active === label}
                  className={`mx-0.5 rounded px-1.5 text-xs font-semibold ${active === label ? 'bg-indigo-600 text-white' : 'bg-indigo-100 text-indigo-800 hover:bg-indigo-200 dark:bg-indigo-900/50 dark:text-indigo-200'}`}
                >
                  {label.slice(1)}
                </button>
              ) : (
                <span
                  key={label}
                  title={unknown.includes(label) ? 'The model cited a source that was not provided' : 'Unknown source'}
                  className="mx-0.5 rounded bg-red-100 px-1.5 text-xs font-semibold text-red-800 dark:bg-red-900/40 dark:text-red-200"
                >
                  {label.slice(1)}?
                </span>
              )
            })}
          </Fragment>
        )
      })}
    </p>
  )
}

function TurnView({ turn }: { turn: Turn }) {
  const [active, setActive] = useState<string | undefined>()
  const source = turn.citations.find((c) => c.label === active)
  const generation = turn.spans.find((s) => s.stage === 'generation')
  return (
    <article className="space-y-3">
      <div className="ml-auto max-w-[85%] rounded-lg bg-indigo-600 px-4 py-2 text-sm text-white">{turn.question}</div>
      <div className="card space-y-3 text-sm" aria-live={turn.status === 'streaming' ? 'polite' : 'off'} aria-busy={turn.status === 'streaming'}>
        {turn.answer === '' && turn.status === 'streaming' && <p className="animate-pulse text-slate-500">Searching your documents…</p>}
        {turn.answer !== '' && (
          <AnswerText text={turn.answer} citations={turn.citations} unknown={turn.unknownLabels} onPick={(l) => setActive(active === l ? undefined : l)} active={active} />
        )}
        {turn.status === 'streaming' && turn.answer !== '' && <span className="inline-block h-4 w-1.5 animate-pulse bg-slate-400 align-middle" aria-hidden />}
        {turn.status === 'stopped' && <p className="text-slate-500">Stopped.</p>}
        {turn.status === 'error' && <p role="alert" className="text-red-600">{turn.error}</p>}
        {turn.abstained && <p className="text-slate-500">Glassbox found nothing in your documents that answers this.</p>}
        {turn.sanitized && <p className="text-xs text-amber-700 dark:text-amber-300">Links and images were removed from this answer for your safety.</p>}
        {turn.unknownLabels.length > 0 && (
          <p className="text-xs text-red-700 dark:text-red-300">The model cited {turn.unknownLabels.join(', ')}, which were not among the sources. Treat that part with caution.</p>
        )}

        {source && (
          <aside className="rounded-md border border-slate-200 bg-slate-50 p-3 dark:border-slate-700 dark:bg-slate-950">
            <h3 className="text-xs font-semibold uppercase text-slate-500">
              {source.title}
              {source.page != null && ` · page ${source.page}`}
            </h3>
            <blockquote className="mt-1 whitespace-pre-wrap text-slate-700 dark:text-slate-300">{source.text}</blockquote>
          </aside>
        )}

        {turn.spans.length > 0 && (
          <details className="text-xs text-slate-500">
            <summary className="cursor-pointer">
              {turn.model ?? 'model'} · {turn.latencyMs != null ? `${(turn.latencyMs / 1000).toFixed(1)} s` : '…'}
              {generation?.detail?.timeToFirstTokenMs != null && ` · first token ${(Number(generation.detail.timeToFirstTokenMs) / 1000).toFixed(1)} s`}
            </summary>
            <ul className="mt-1 space-y-0.5">
              {turn.spans.map((s, i) => (
                <li key={i}>{s.stage}: {s.millis} ms</li>
              ))}
            </ul>
          </details>
        )}
        {turn.traceId && (
          <Link to={`/xray/${turn.traceId}`} className="inline-block text-xs text-indigo-600 hover:underline">
            Open in X-Ray →
          </Link>
        )}
      </div>
    </article>
  )
}

export default function Chat() {
  const { current } = useWorkspace()
  const [turns, setTurns] = useState<Turn[]>([])
  const [question, setQuestion] = useState('')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const abort = useRef<AbortController | null>(null)
  const bottom = useRef<HTMLDivElement>(null)
  const nextId = useRef(0)

  const docs = useQuery({ queryKey: ['documents', current?.id], queryFn: () => api.documents(), enabled: !!current })
  const readyCount = docs.data?.items.filter((d) => d.status === 'READY').length ?? 0

  // Follow the answer as it grows.
  useEffect(() => {
    bottom.current?.scrollIntoView({ block: 'end' })
  }, [turns])

  // Leaving the page stops generation, which frees the server's single answer slot.
  useEffect(() => () => abort.current?.abort(), [])

  function update(id: number, fn: (t: Turn) => Turn) {
    setTurns((all) => all.map((t) => (t.id === id ? fn(t) : t)))
  }

  async function ask(e?: FormEvent) {
    e?.preventDefault()
    const q = question.trim()
    if (!q || busy) return
    setNotice(null)
    setBusy(true)
    const id = nextId.current++
    const ctrl = new AbortController()
    abort.current = ctrl
    setTurns((t) => [...t, { id, question: q, answer: '', status: 'streaming', citations: [], unknownLabels: [], spans: [] }])
    setQuestion('')

    try {
      await streamChat(q, (ev) => {
        const data = ev.data ? JSON.parse(ev.data) : {}
        if (ev.event === 'token') update(id, (t) => ({ ...t, answer: t.answer + data.t }))
        else if (ev.event === 'trace') update(id, (t) => ({ ...t, spans: [...t.spans, data as TraceSpan] }))
        else if (ev.event === 'citations') update(id, (t) => ({ ...t, citations: data.citations, unknownLabels: data.unknown }))
        else if (ev.event === 'done')
          // The server's final text has links and images stripped; it replaces what was streamed.
          update(id, (t) => ({ ...t, status: 'done', answer: data.answer, sanitized: data.sanitized, abstained: data.abstained, model: data.model, latencyMs: data.latencyMs, traceId: data.traceId }))
        else if (ev.event === 'error') update(id, (t) => ({ ...t, status: 'error', error: data.message, traceId: data.traceId }))
      }, ctrl.signal)
      update(id, (t) => (t.status === 'streaming' ? { ...t, status: 'error', error: 'The connection closed before the answer finished.' } : t))
    } catch (err) {
      if (ctrl.signal.aborted) {
        update(id, (t) => ({ ...t, status: 'stopped' }))
      } else if (err instanceof ApiError && err.status === 429) {
        // Refused before streaming started: drop the empty turn and give the question back.
        setTurns((all) => all.filter((t) => t.id !== id))
        setQuestion(q)
        setNotice(`${err.message}${err.retryAfterSeconds ? ` (try again in ${err.retryAfterSeconds} s)` : ''}`)
      } else {
        update(id, (t) => ({ ...t, status: 'error', error: err instanceof Error ? err.message : 'Something went wrong' }))
      }
    } finally {
      setBusy(false)
      abort.current = null
    }
  }

  function onKey(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      void ask()
    }
  }

  if (!current) return null

  return (
    <section className="flex min-h-[70vh] flex-col gap-6">
      <h1 className="text-lg font-semibold">Chat</h1>

      <div className="flex-1 space-y-8">
        {turns.length === 0 && (
          <div className="card text-center text-sm text-slate-500">
            {docs.isSuccess && readyCount === 0 ? (
              <>
                <p>There is nothing to search yet.</p>
                <p className="mt-1">
                  <Link to="/documents" className="text-indigo-600 hover:underline">Upload documents</Link> and wait for them to be ready.
                </p>
              </>
            ) : (
              <p>Ask a question about your {readyCount || ''} document{readyCount === 1 ? '' : 's'}. Answers cite their sources.</p>
            )}
          </div>
        )}
        {turns.map((t) => <TurnView key={t.id} turn={t} />)}
        <div ref={bottom} />
      </div>

      {notice && <p role="alert" className="rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-800 dark:bg-amber-900/30 dark:text-amber-200">{notice}</p>}

      <form onSubmit={ask} className="sticky bottom-0 flex gap-2 bg-slate-50 pb-4 pt-2 dark:bg-slate-950">
        <label htmlFor="question" className="sr-only">Your question</label>
        <textarea
          id="question"
          className="input min-h-[2.75rem] flex-1 resize-none"
          rows={2}
          maxLength={2000}
          placeholder="Ask about your documents…"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={onKey}
        />
        {busy ? (
          <button type="button" className="btn bg-slate-600 hover:bg-slate-500" onClick={() => abort.current?.abort()}>Stop</button>
        ) : (
          <button className="btn" disabled={!question.trim()}>Ask</button>
        )}
      </form>
    </section>
  )
}
