import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Inbox, Loader2, ScanSearch, X } from 'lucide-react'
import { Link } from 'react-router-dom'
import { DocumentViewer } from '../components/DocumentViewer'
import { api, ApiError, type FeedbackDetail, type FeedbackItem, type SpanInput } from '../lib/api'
import { addSpan, canConvert, defaultType, fromFeedbackText, sameSpan, toggleSpan } from '../lib/feedback'
import { queryKeys } from '../lib/queryKeys'
import { TYPE_LABEL } from '../lib/testsets'
import { useWorkspace } from '../lib/workspace'

const STATUS: Record<string, string> = { OPEN: 'Open', CONVERTED: 'In a test set', DISMISSED: 'Dismissed' }

function Triage({ item }: { item: FeedbackItem }) {
  const qc = useQueryClient()
  const detail = useQuery({ queryKey: queryKeys.feedbackItem(item.id), queryFn: () => api.feedbackDetail(item.id) })
  const sets = useQuery({ queryKey: queryKeys.testsets(undefined), queryFn: api.testsets })
  const usable = (sets.data ?? []).filter((t) => t.status === 'FINAL' || t.status === 'DRAFT')
  const [marked, setMarked] = useState<SpanInput[]>([])
  const [docId, setDocId] = useState('')
  const [setId, setSetId] = useState('')
  const [expected, setExpected] = useState('')
  const [qtypeChoice, setQtypeChoice] = useState('')
  const qtype = qtypeChoice || defaultType(marked)
  const target = setId || usable[0]?.id || ''
  const done = () => { qc.invalidateQueries({ queryKey: queryKeys.feedbackList(undefined) }); qc.invalidateQueries({ queryKey: queryKeys.feedbackSummary(undefined) }); qc.invalidateQueries({ queryKey: queryKeys.testsets(undefined) }) }

  const diagnose = useMutation({ mutationFn: (spans: SpanInput[]) => api.diagnoseFeedback(item.id, spans) })
  const mark = (next: SpanInput[]) => { setMarked(next); diagnose.mutate(next) }
  const convert = useMutation({ mutationFn: () => api.convertFeedback(item.id, { testsetId: target, expectedAnswer: expected || undefined, qtype, spans: marked }), onSuccess: done })
  const dismiss = useMutation({ mutationFn: () => api.dismissFeedback(item.id), onSuccess: done })
  const d: FeedbackDetail | undefined = detail.data
  const err = [convert.error, dismiss.error].map((e) => (e instanceof ApiError ? e.message : e ? 'That did not work.' : null)).find(Boolean)
  const documents = [...new Map((d?.retrieved ?? []).map((p) => [p.documentId, p.title])).entries()]
  const open = item.status === 'OPEN'

  return (
    <div className="space-y-4">
      <div className="glass space-y-2 rounded-2xl p-4">
        <p className="eyebrow">Question</p>
        <p className="font-semibold">{item.question}</p>
        <p className="eyebrow pt-1">Answer</p>
        <p className="whitespace-pre-wrap text-sm">{item.answer || '(no answer)'}</p>
        {item.comment && <p className="text-sm"><span className="font-medium">Comment:</span> {item.comment}</p>}
        {item.traceId && <Link to={`/xray/${item.traceId}`} className="btn-ghost -ml-2 !px-2 text-xs"><ScanSearch className="h-3.5 w-3.5" /> How this was made</Link>}
        {d?.warnings.map((w) => <p key={w} className="text-xs text-warn">{w}</p>)}
      </div>

      {open && d && (
        <>
          <div className="glass space-y-3 rounded-2xl p-4">
            <h3 className="text-sm font-semibold">Where is the right answer?</h3>
            <p className="text-xs text-muted">Mark the passages that hold it. The failure report then says at which stage the pipeline lost them. If the documents do not answer the question, leave this empty and save it as unanswerable.</p>
            {d.retrieved.length === 0 ? <p className="text-sm text-muted">Nothing was retrieved for this question.</p> : (
              <ul className="space-y-1.5">
                {d.retrieved.map((p) => {
                  const span = { documentId: p.documentId, charStart: p.charStart, charEnd: p.charEnd }
                  const on = marked.some((m) => sameSpan(m, span))
                  return (
                    <li key={`${p.documentId}-${p.charStart}`} className={`flex items-start gap-2 rounded-lg border p-2 text-sm ${on ? 'border-accent bg-accent/5' : 'border-[var(--line)]'}`}>
                      <span className="min-w-0 flex-1"><span className="block text-xs text-muted">{p.title}{p.page ? `, page ${p.page}` : ''} · {p.disposition === 'IN_CONTEXT' ? 'in the prompt' : p.disposition === 'DROPPED' ? 'dropped from the prompt' : 'not in the top results'}</span>{p.snippet}</span>
                      <button className="btn-ghost shrink-0 !px-2 text-xs" onClick={() => mark(toggleSpan(marked, span))} aria-pressed={on}>{on ? <><Check className="h-3.5 w-3.5" /> Evidence</> : 'This is it'}</button>
                    </li>
                  )
                })}
              </ul>
            )}
            {documents.length > 0 && (
              <details>
                <summary className="cursor-pointer text-xs font-semibold">The passage is not among these: select it in a document</summary>
                <select className="input-glass mt-2 !w-auto !py-1 text-sm" value={docId} onChange={(e) => setDocId(e.target.value)} aria-label="Document">
                  <option value="">Choose a document</option>{documents.map(([id, title]) => <option key={id} value={id}>{title}</option>)}
                </select>
                {docId && <DocumentViewer documentId={docId} highlights={marked.filter((m) => m.documentId === docId).map((m, i) => ({ start: m.charStart, end: m.charEnd, kind: 'primary' as const, label: `evidence ${i + 1}`, documentId: docId }))}
                  className="mt-2 max-h-72 overflow-y-auto rounded-lg border border-[var(--line)] p-3 text-sm" onSelect={(range, documentId) => mark(addSpan(marked, { documentId, charStart: range.start, charEnd: range.end }))} />}
              </details>
            )}
            {diagnose.data && (
              <p className={`rounded-lg border p-2.5 text-sm ${diagnose.data.retrievalProblem ? 'border-warn/50' : 'border-[var(--line)]'}`}>
                {diagnose.data.stage && <span className="chip mr-2">{diagnose.data.stage.toLowerCase().replace('_', ' ')}</span>}{diagnose.data.message}
              </p>
            )}
          </div>

          <div className="glass space-y-3 rounded-2xl p-4">
            <h3 className="text-sm font-semibold">Save as a test question</h3>
            <div className="grid gap-3 sm:grid-cols-2">
              <label className="space-y-1 text-xs text-muted">Test set
                <select className="input-glass !py-1.5 text-sm" value={target} onChange={(e) => setSetId(e.target.value)}>{usable.map((t) => <option key={t.id} value={t.id}>{t.name} v{t.version} ({t.status.toLowerCase()})</option>)}</select>
              </label>
              <label className="space-y-1 text-xs text-muted">Type
                <select className="input-glass !py-1.5 text-sm" value={qtype} onChange={(e) => setQtypeChoice(e.target.value)}>{(['FACTUAL', 'NUMERIC', 'MULTI_HOP', 'COMPARISON', 'UNANSWERABLE'] as const).map((t) => <option key={t} value={t}>{TYPE_LABEL[t]}</option>)}</select>
              </label>
            </div>
            {qtype !== 'UNANSWERABLE' && <label className="block space-y-1 text-xs text-muted">Expected answer<textarea className="input-glass text-sm" rows={2} value={expected} onChange={(e) => setExpected(e.target.value)} /></label>}
            {usable.find((t) => t.id === target)?.status === 'FINAL' && <p className="text-xs text-muted">That version is final, so a new draft version is made and the question goes into it.</p>}
            {err && <p role="alert" className="text-sm text-red-500">{err}</p>}
            <div className="flex gap-2">
              <button className="btn-primary" disabled={!canConvert(marked, qtype, expected, target) || convert.isPending} onClick={() => convert.mutate()}>{convert.isPending && <Loader2 className="h-4 w-4 animate-spin" />} Add to the test set</button>
              <button className="btn-ghost" disabled={dismiss.isPending} onClick={() => dismiss.mutate()}><X className="h-4 w-4" /> Dismiss</button>
            </div>
          </div>
        </>
      )}
      {convert.data && <p className="text-sm">Added to <Link className="font-medium text-accent underline-offset-4 hover:underline" to={`/testsets/${convert.data.testsetId}/review`}>the test set</Link>{convert.data.newVersion ? ' (a new draft version)' : ''}. It is accepted as written; review it there.</p>}
      {!open && <p className="text-sm text-muted">{STATUS[item.status]}.</p>}
    </div>
  )
}

export default function Feedback() {
  const { current, loading } = useWorkspace()
  const canTriage = current != null && current.role !== 'VIEWER'
  const [status, setStatus] = useState('OPEN')
  const [pick, setPick] = useState('')
  const list = useQuery({ queryKey: [...queryKeys.feedbackList(current?.id), status], queryFn: () => api.feedbackList(status), enabled: canTriage })
  const summary = useQuery({ queryKey: queryKeys.feedbackSummary(current?.id), queryFn: api.feedbackSummary, enabled: canTriage })
  if (loading) return <div className="p-6"><div className="skeleton h-10 w-60" /></div>
  if (!canTriage) return <p className="p-6 text-sm text-muted">Reviewing feedback needs the editor role. You can still rate answers in the chat.</p>
  const items = list.data ?? []
  const chosen = items.find((i) => i.id === pick) ?? items[0]

  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto w-full max-w-5xl space-y-5 p-4 sm:p-6">
        <header>
          <h1 className="flex items-center gap-2 text-[1.65rem] font-semibold tracking-tight"><Inbox className="h-6 w-6 text-accent" /> Feedback</h1>
          <p className="mt-1 text-sm text-muted">Answers people marked as bad. Mark where the right answer is, and it becomes a test question, so the test set grows from real failures.</p>
          {summary.data && <p className="mt-1 text-sm">{fromFeedbackText(summary.data)} <span className="text-muted">{summary.data.open} open, {summary.data.converted} added, {summary.data.dismissed} dismissed.</span></p>}
        </header>
        <div className="flex gap-1.5" role="tablist" aria-label="Status">
          {['OPEN', 'CONVERTED', 'DISMISSED'].map((s) => <button key={s} role="tab" aria-selected={status === s} className={`btn-ghost !border ${status === s ? '!border-accent' : '!border-[var(--line)]'}`} onClick={() => { setStatus(s); setPick('') }}>{STATUS[s]}</button>)}
        </div>
        {list.isLoading ? <div className="skeleton h-24" /> : items.length === 0 ? <p className="glass rounded-2xl p-6 text-sm text-muted">Nothing here. Use the thumbs under an answer in the chat to report a bad one.</p> : (
          <div className="grid gap-4 lg:grid-cols-[18rem_1fr]">
            <ul className="space-y-1.5" aria-label="Feedback items">
              {items.map((i) => (
                <li key={i.id}><button onClick={() => setPick(i.id)} className={`w-full rounded-xl border p-2.5 text-left text-sm ${chosen?.id === i.id ? 'border-accent bg-accent/5' : 'border-[var(--line)]'}`}>
                  <span className="line-clamp-2 font-medium">{i.question}</span><span className="block text-xs text-muted">{i.rating === 'DOWN' ? 'Bad answer' : 'Good answer'} · {new Date(i.createdAt).toLocaleDateString()}</span></button></li>
              ))}
            </ul>
            {chosen && <Triage key={chosen.id} item={chosen} />}
          </div>
        )}
      </div>
    </div>
  )
}
