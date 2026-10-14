import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { DocumentViewer } from '../components/DocumentViewer'
import { api, type CandidateView, type Trace, type TraceSpan } from '../lib/api'
import { answerSentences, type Highlight } from '../lib/highlight'

const STAGE_LABEL: Record<string, string> = {
  query_embedding: 'Embed query',
  retrieval: 'Hybrid search',
  rerank: 'Rerank',
  context: 'Pack context',
  generation: 'Generate answer',
}

interface Selection {
  documentId: string
  start: number
  end: number
  label: string
  key: number
}

const DISPOSITION: Record<CandidateView['disposition'], { text: string; cls: string }> = {
  IN_CONTEXT: { text: 'in prompt', cls: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300' },
  DROPPED: { text: 'dropped', cls: 'bg-orange-100 text-orange-800 dark:bg-orange-900/40 dark:text-orange-300' },
  CUT: { text: 'cut', cls: 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300' },
}

function fmt(n: number | undefined, digits = 3) {
  return n == null ? '–' : n.toFixed(digits)
}

function Timeline({ spans, total }: { spans: TraceSpan[]; total?: number }) {
  const sum = Math.max(1, spans.reduce((a, s) => a + s.millis, 0))
  return (
    <section aria-label="Stage timeline" className="space-y-2">
      <h2 className="text-sm font-semibold">Where the time went</h2>
      <div className="flex h-6 overflow-hidden rounded-md bg-slate-100 dark:bg-slate-800" role="img" aria-label="Time per stage">
        {spans.map((s, i) => (
          <div
            key={i}
            style={{ width: `${Math.max(1.5, (s.millis / sum) * 100)}%` }}
            className={['bg-indigo-400', 'bg-sky-400', 'bg-violet-400', 'bg-teal-400', 'bg-amber-400'][i % 5]}
            title={`${STAGE_LABEL[s.stage] ?? s.stage}: ${s.millis} ms`}
          />
        ))}
      </div>
      <ul className="grid gap-x-6 gap-y-1 text-xs text-slate-600 sm:grid-cols-2 dark:text-slate-300">
        {spans.map((s, i) => (
          <li key={i} className="flex justify-between gap-2">
            <span>{STAGE_LABEL[s.stage] ?? s.stage}</span>
            <span className="tabular-nums">{s.millis >= 1000 ? `${(s.millis / 1000).toFixed(1)} s` : `${s.millis} ms`}</span>
          </li>
        ))}
      </ul>
      {total != null && <p className="text-xs text-slate-500">Total {(total / 1000).toFixed(1)} s</p>}
    </section>
  )
}

function ContextPanel({ trace, onPick }: { trace: Trace; onPick: (c: CandidateView) => void }) {
  const ctx = trace.data.context
  if (!ctx) return null
  const byId = new Map(trace.data.candidates.map((c) => [c.chunkId, c]))
  const pct = Math.min(100, (ctx.tokensUsed / Math.max(1, ctx.budget)) * 100)
  return (
    <section aria-label="Context" className="space-y-2">
      <h2 className="text-sm font-semibold">What reached the prompt</h2>
      <div className="h-3 overflow-hidden rounded bg-slate-100 dark:bg-slate-800" role="img" aria-label={`${ctx.tokensUsed} of ${ctx.budget} tokens used`}>
        <div className="h-full bg-emerald-500" style={{ width: `${pct}%` }} />
      </div>
      <p className="text-xs text-slate-500">{ctx.tokensUsed} of {ctx.budget} tokens used by {ctx.included.length} chunk{ctx.included.length === 1 ? '' : 's'}</p>
      {ctx.dropped.length > 0 && (
        <ul className="space-y-1 text-xs">
          {ctx.dropped.map((d) => {
            const c = byId.get(d.chunkId)
            return (
              <li key={d.chunkId} className="rounded border border-orange-200 bg-orange-50 px-2 py-1 dark:border-orange-900 dark:bg-orange-950/30">
                <button type="button" className="text-left hover:underline" onClick={() => c && onPick(c)}>
                  <strong>Dropped ({d.reason.toLowerCase()})</strong>: {c ? `${c.title}${c.page ? `, p.${c.page}` : ''}` : 'chunk'}
                </button>
                <div className="text-slate-600 dark:text-slate-300">{d.detail}</div>
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}

export default function XRay() {
  const { traceId } = useParams()
  const trace = useQuery({ queryKey: ['trace', traceId], queryFn: () => api.trace(traceId!), enabled: !!traceId })
  const [sel, setSel] = useState<Selection | null>(null)
  const [promptOpen, setPromptOpen] = useState(false)
  const [copied, setCopied] = useState(false)

  const t = trace.data
  const candidates = useMemo(() => t?.data.candidates ?? [], [t])
  const citations = t?.data.citations ?? []
  const sentences = useMemo(() => answerSentences(t?.answer ?? ''), [t?.answer])

  function pickCandidate(c: CandidateView) {
    setSel({ documentId: c.documentId, start: c.charStart, end: c.charEnd, label: c.label ?? `candidate #${c.rankedPosition ?? '?'}`, key: Date.now() })
  }
  function pickLabels(labels: string[]) {
    const c = citations.find((x) => labels.includes(x.label))
    if (c) setSel({ documentId: c.documentId, start: c.charStart, end: c.charEnd, label: c.label, key: Date.now() })
  }

  // In the viewer: the selected passage is "primary"; other chunks that reached the prompt are "secondary"; the rest of
  // the retrieved chunks from the same document are faint "candidate" marks, which shows where each one sits.
  const highlights: Highlight[] = useMemo(() => {
    if (!sel) return []
    const out: Highlight[] = []
    for (const c of candidates) {
      if (c.documentId !== sel.documentId) continue
      out.push({ start: c.charStart, end: c.charEnd, kind: c.disposition === 'IN_CONTEXT' ? 'secondary' : 'candidate', label: c.label ?? `candidate #${c.rankedPosition ?? '–'}` })
    }
    out.push({ start: sel.start, end: sel.end, kind: 'primary', label: sel.label })
    return out
  }, [sel, candidates])

  if (trace.isLoading) return <div className="h-64 animate-pulse rounded-lg bg-slate-200 dark:bg-slate-800" aria-label="Loading trace" />
  if (trace.isError || !t) return <p role="alert" className="text-red-600">This trace could not be found. It may have expired or belong to another workspace.</p>

  const cfg = t.data.config
  const statusCls = t.status === 'OK' ? 'text-emerald-700 dark:text-emerald-300' : t.status === 'ABSTAINED' ? 'text-slate-500' : 'text-red-600'

  return (
    <div className="space-y-8">
      <header className="space-y-1">
        <Link to="/xray" className="text-xs text-indigo-600 hover:underline">← All traces</Link>
        <h1 className="text-lg font-semibold">{t.question}</h1>
        <p className="text-xs text-slate-500">
          <span className={`font-semibold ${statusCls}`}>{t.status}</span> · {t.model ?? 'no model call'} · {new Date(t.createdAt).toLocaleString()}
          {t.data.usage && ` · ~${t.data.usage.promptTokens} prompt tokens, ~${t.data.usage.answerTokens} answer tokens (local model: no cost)`}
        </p>
        {t.data.error && <p role="alert" className="text-sm text-red-600">{t.data.error}</p>}
      </header>

      <div className="grid gap-8 lg:grid-cols-[minmax(0,1fr)_minmax(0,26rem)]">
        <div className="space-y-8">
          <Timeline spans={t.spans} total={t.latencyMs} />

          {t.answer && (
            <section aria-label="Answer" className="space-y-2">
              <h2 className="text-sm font-semibold">Answer <span className="font-normal text-slate-500">· click a sentence to see its source</span></h2>
              <p className="card whitespace-pre-wrap text-sm leading-relaxed">
                {sentences.map((s, i) =>
                  s.labels.length > 0 ? (
                    <button key={i} type="button" onClick={() => pickLabels(s.labels)} className="rounded text-left hover:bg-amber-100 dark:hover:bg-amber-900/40">
                      {s.text}
                    </button>
                  ) : (
                    <span key={i}>{s.text}</span>
                  ),
                )}
              </p>
              {t.data.unknownCitations.length > 0 && (
                <p className="text-xs text-red-700 dark:text-red-300">Cited labels that were not in the context: {t.data.unknownCitations.join(', ')}</p>
              )}
            </section>
          )}

          <ContextPanel trace={t} onPick={pickCandidate} />

          <section aria-label="Retrieved candidates" className="space-y-2">
            <h2 className="text-sm font-semibold">Retrieved candidates ({candidates.length})</h2>
            {cfg && (
              <p className="text-xs text-slate-500">
                {cfg.retrieval.mode.toLowerCase()} search · fused with RRF (k={cfg.retrieval.rrfK}, weights {cfg.retrieval.wVec}/{cfg.retrieval.wKw}) ·
                keyword match: {cfg.retrieval.keywordMatch.toLowerCase()} · reranker: {cfg.reranker.toLowerCase().replace('_', '-')} · top {cfg.topN}
              </p>
            )}
            {candidates.length === 0 ? (
              <p className="card text-sm text-slate-500">Nothing was retrieved{t.status === 'ABSTAINED' ? ', so no model was called' : ''}.</p>
            ) : (
              <div className="overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-800">
                <table className="w-full text-left text-xs">
                  <caption className="sr-only">Candidates in fused order, with the scores that ranked them</caption>
                  <thead className="bg-slate-100 uppercase text-slate-500 dark:bg-slate-900">
                    <tr>
                      <th scope="col" className="px-2 py-2">#</th>
                      <th scope="col" className="px-2 py-2">Passage</th>
                      <th scope="col" className="px-2 py-2">Vector</th>
                      <th scope="col" className="px-2 py-2">Keyword</th>
                      <th scope="col" className="px-2 py-2">Fused</th>
                      <th scope="col" className="px-2 py-2">Rerank</th>
                      <th scope="col" className="px-2 py-2">Outcome</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-200 dark:divide-slate-800">
                    {candidates.map((c, i) => (
                      <tr key={c.chunkId} className={sel && sel.start === c.charStart && sel.documentId === c.documentId ? 'bg-amber-50 dark:bg-amber-950/30' : ''}>
                        <td className="px-2 py-2 tabular-nums text-slate-500">{i + 1}</td>
                        <td className="max-w-[18rem] px-2 py-2">
                          <button type="button" onClick={() => pickCandidate(c)} className="text-left hover:underline">
                            <span className="font-medium">{c.title}{c.page ? `, p.${c.page}` : ''}</span>
                            <span className="block truncate text-slate-500">{c.snippet}</span>
                          </button>
                        </td>
                        <td className="px-2 py-2 tabular-nums">{c.vecRank != null ? `#${c.vecRank} · ${fmt(c.vecDistance)}` : '–'}</td>
                        <td className="px-2 py-2 tabular-nums">{c.kwRank != null ? `#${c.kwRank} · ${fmt(c.kwScore)}` : '–'}</td>
                        <td className="px-2 py-2 tabular-nums">{fmt(c.fusedScore, 4)}</td>
                        <td className="px-2 py-2 tabular-nums">{c.rankedPosition != null ? `#${c.rankedPosition}${c.rerankScore != null ? ` · ${fmt(c.rerankScore, 2)}` : ''}` : '–'}</td>
                        <td className="px-2 py-2" title={c.reason}>
                          <span className={`rounded-full px-2 py-0.5 font-medium ${DISPOSITION[c.disposition].cls}`}>
                            {DISPOSITION[c.disposition].text}{c.label ? ` ${c.label}` : ''}
                          </span>
                          {c.reason && <span className="mt-0.5 block max-w-[14rem] text-slate-500">{c.reason}</span>}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {t.data.prompt && (
            <section aria-label="Prompt" className="space-y-2">
              <div className="flex items-center gap-3">
                <h2 className="text-sm font-semibold">The exact prompt</h2>
                <button type="button" className="text-xs text-indigo-600 hover:underline" aria-expanded={promptOpen} onClick={() => setPromptOpen(!promptOpen)}>
                  {promptOpen ? 'Hide' : 'Show'}
                </button>
                {promptOpen && (
                  <button
                    type="button"
                    className="text-xs text-indigo-600 hover:underline"
                    onClick={() => {
                      void navigator.clipboard?.writeText(t.data.prompt!.map((m) => `[${m.role}]\n${m.content}`).join('\n\n')).then(() => setCopied(true))
                    }}
                  >
                    {copied ? 'Copied' : 'Copy'}
                  </button>
                )}
              </div>
              {promptOpen && t.data.prompt.map((m, i) => (
                <div key={i}>
                  <div className="text-xs font-semibold uppercase text-slate-500">{m.role}</div>
                  <pre className="max-h-96 overflow-auto whitespace-pre-wrap rounded-md border border-slate-200 bg-slate-50 p-3 text-xs dark:border-slate-700 dark:bg-slate-950">{m.content}</pre>
                </div>
              ))}
            </section>
          )}
        </div>

        <aside aria-label="Source document" className="lg:sticky lg:top-4 lg:self-start">
          {sel ? (
            <div className="space-y-2">
              <p className="text-xs text-slate-500">
                Showing <strong>{sel.label}</strong>. <span className="rounded bg-amber-300 px-1 text-slate-900">Selected</span>{' '}
                <span className="rounded bg-indigo-200 px-1 text-slate-900">In prompt</span>{' '}
                <span className="rounded bg-slate-200 px-1 dark:bg-slate-600">Other candidates</span>
              </p>
              <DocumentViewer documentId={sel.documentId} highlights={highlights} scrollKey={sel.key} className="max-h-[70vh]" />
            </div>
          ) : (
            <div className="card text-sm text-slate-500">
              Select a sentence of the answer, a candidate, or a dropped chunk to open its document here, scrolled to the exact passage.
            </div>
          )}
        </aside>
      </div>
    </div>
  )
}
