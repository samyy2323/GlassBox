import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, ChevronLeft, GitCompare, Loader2, Lock, Pencil, Plus, RotateCcw, X } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { AddQuestionDialog } from '../components/AddQuestionDialog'
import { Dialog } from '../components/Dialog'
import { DiffDialog } from '../components/DiffDialog'
import { DocumentViewer } from '../components/DocumentViewer'
import { api, ApiError, type QualityFlag, type QuestionPatch, type QuestionType, type ReviewStatus, type SpanInput, type TestQuestion } from '../lib/api'
import type { Highlight } from '../lib/highlight'
import { queryKeys } from '../lib/queryKeys'
import { actionForKey, afterDecision, filterQuestions, isTypingTarget, moveIndex, NO_FILTER, type ReviewFilter } from '../lib/review'
import type { Range } from '../lib/selection'
import { addEvidence, FLAG_LABEL, percent, progressPercent, removeEvidence, replaceEvidence, REVIEW_LABEL, spanInputs, TYPE_LABEL } from '../lib/testsets'
import { useWorkspace } from '../lib/workspace'
import { SetStatus } from './TestSets'

const REVIEW_DOT: Record<ReviewStatus, string> = { PENDING: 'bg-warn', ACCEPTED: 'bg-ok', EDITED: 'bg-accent', REJECTED: 'bg-danger' }
const errorText = (e: unknown, fallback: string) => (e instanceof ApiError ? e.message : fallback)

function Kbd({ children }: { children: string }) {
  return <span className="kbd ml-1.5">{children}</span>
}

export default function TestSetReview() {
  const { id = '' } = useParams()
  const { current } = useWorkspace()
  const qc = useQueryClient()
  const navigate = useNavigate()

  const ts = useQuery({
    queryKey: queryKeys.testset(id),
    queryFn: () => api.testset(id),
    enabled: !!current,
    refetchInterval: (q) => (q.state.data?.status === 'GENERATING' ? 3000 : false),
  })
  const generating = ts.data?.status === 'GENERATING'
  const qs = useQuery({ queryKey: queryKeys.testsetQuestions(id), queryFn: () => api.testsetQuestions(id), enabled: !!current, refetchInterval: generating ? 3000 : false })
  const all = useQuery({ queryKey: queryKeys.testsets(current?.id), queryFn: api.testsets, enabled: !!current })
  const docs = useQuery({ queryKey: queryKeys.documents(current?.id), queryFn: () => api.documents(), enabled: !!current })

  const [filter, setFilter] = useState<ReviewFilter>(NO_FILTER)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState<{ question: string; expectedAnswer: string; qtype: QuestionType }>({ question: '', expectedAnswer: '', qtype: 'FACTUAL' })
  const [spanDraft, setSpanDraft] = useState<SpanInput[] | null>(null)
  const [focus, setFocus] = useState(0)
  const [selection, setSelection] = useState<{ range: Range; documentId: string } | null>(null)
  const [dialog, setDialog] = useState<null | 'add' | 'diff' | 'finalize'>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const questions = useMemo(() => qs.data?.items ?? [], [qs.data])
  const shown = useMemo(() => filterQuestions(questions, filter), [questions, filter])
  const found = shown.findIndex((q) => q.id === selectedId)
  const index = Math.max(0, found)
  const q: TestQuestion | undefined = shown[index]
  const set = ts.data
  const canEditRole = current != null && current.role !== 'VIEWER'
  const editable = set?.status === 'DRAFT' && canEditRole

  const versions = (all.data ?? []).filter((t) => t.name === set?.name && t.id !== set?.id).sort((a, b) => b.version - a.version)
  const siblings = (all.data ?? []).filter((t) => t.name === set?.name).sort((a, b) => b.version - a.version)
  const titleOf = (documentId: string) => q?.evidence.find((e) => e.documentId === documentId)?.title ?? docs.data?.items.find((d) => d.id === documentId)?.title ?? 'document'

  // Starting a different question starts from a clean slate.
  useEffect(() => {
    setFocus(0)
    setSelection(null)
    setEditing(false)
    setSpanDraft(null)
  }, [q?.id])

  const patch = useMutation({
    mutationFn: ({ questionId, body }: { questionId: string; body: QuestionPatch }) => api.patchQuestion(questionId, body),
    onSuccess: (updated) => {
      qc.setQueryData<{ items: TestQuestion[]; cursor?: string }>(queryKeys.testsetQuestions(id), (old) => old && { ...old, items: old.items.map((x) => (x.id === updated.id ? updated : x)) })
      void qc.invalidateQueries({ queryKey: queryKeys.testset(id) })
      void qc.invalidateQueries({ queryKey: queryKeys.testsets(current?.id) })
      setNotice(null)
    },
    onError: (e) => setNotice(errorText(e, 'That change could not be saved.')),
  })

  function decide(status: 'ACCEPTED' | 'REJECTED' | 'PENDING') {
    if (!q || !editable || patch.isPending) return
    const nextId = status === 'PENDING' ? undefined : shown[afterDecision(shown, index)]?.id
    patch.mutate({ questionId: q.id, body: { reviewStatus: status } }, { onSuccess: () => { if (nextId && nextId !== q.id) setSelectedId(nextId) } })
  }

  function startEdit() {
    if (!q || !editable) return
    setDraft({ question: q.question, expectedAnswer: q.expectedAnswer, qtype: q.qtype })
    setSpanDraft(spanInputs(q.evidence))
    setEditing(true)
  }

  function saveEdit() {
    if (!q || patch.isPending) return
    patch.mutate({ questionId: q.id, body: { question: draft.question, expectedAnswer: draft.expectedAnswer, qtype: draft.qtype, spans: spanDraft ?? undefined } }, { onSuccess: () => setEditing(false) })
  }

  function move(delta: number) {
    const next = shown[moveIndex(index, delta, shown.length)]
    if (next) setSelectedId(next.id)
  }

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (dialog || editing || isTypingTarget(e.target)) return
      const action = actionForKey(e)
      if (!action) return
      e.preventDefault()
      if (action === 'next') move(1)
      else if (action === 'prev') move(-1)
      else if (action === 'accept') decide('ACCEPTED')
      else if (action === 'reject') decide('REJECTED')
      else if (action === 'reset') decide('PENDING')
      else if (action === 'edit') startEdit()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const bulk = useMutation({
    mutationFn: () => api.bulkAccept(id),
    onSuccess: (r) => {
      setNotice(`${r.accepted} question${r.accepted === 1 ? '' : 's'} accepted.`)
      void qc.invalidateQueries({ queryKey: queryKeys.testsetQuestions(id) })
      void qc.invalidateQueries({ queryKey: queryKeys.testset(id) })
    },
    onError: (e) => setNotice(errorText(e, 'Could not accept the questions.')),
  })
  const finalize = useMutation({
    mutationFn: (allowPending: boolean) => api.finalizeTestset(id, allowPending),
    onSuccess: () => {
      setDialog(null)
      void qc.invalidateQueries({ queryKey: queryKeys.testset(id) })
      void qc.invalidateQueries({ queryKey: queryKeys.testsets(current?.id) })
    },
  })
  const newVersion = useMutation({
    mutationFn: () => api.newTestsetVersion(id),
    onSuccess: (t) => {
      void qc.invalidateQueries({ queryKey: queryKeys.testsets(current?.id) })
      navigate(`/testsets/${t.id}/review`)
    },
    onError: (e) => setNotice(errorText(e, 'Could not create a new version.')),
  })

  if (ts.isPending || qs.isPending) {
    return <div className="mx-auto w-full max-w-6xl space-y-4 p-6" aria-label="Loading test set"><div className="skeleton h-10 w-72" /><div className="skeleton h-96" /></div>
  }
  if (ts.isError || !set) return <p role="alert" className="p-6 text-sm">This test set could not be found. It may belong to another workspace.</p>

  const s = set.stats
  const unflagged = questions.filter((x) => x.reviewStatus === 'PENDING' && x.flags.length === 0).length
  const evidence: SpanInput[] = editing && spanDraft ? spanDraft : q ? spanInputs(q.evidence) : []
  const focusSpan = evidence[Math.min(focus, evidence.length - 1)]
  const highlights: Highlight[] = focusSpan
    ? evidence.map((e, i) => ({ start: e.charStart, end: e.charEnd, kind: i === focus ? ('primary' as const) : ('secondary' as const), label: `evidence ${i + 1}`, documentId: e.documentId }))
        .filter((h) => h.documentId === focusSpan.documentId)
    : []

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <header className="space-y-3 border-b border-[var(--line)] px-4 py-3 sm:px-6">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          <Link to="/testsets" className="btn-ghost !px-2" aria-label="All test sets"><ChevronLeft className="h-4 w-4" /></Link>
          <h1 className="min-w-0 truncate text-[1.6rem] leading-tight">{set.name}</h1>
          {siblings.length > 1 ? (
            <select className="input-glass !w-auto !py-1 text-sm" value={set.id} onChange={(e) => navigate(`/testsets/${e.target.value}/review`)} aria-label="Version">
              {siblings.map((v) => <option key={v.id} value={v.id}>Version {v.version} ({v.status.toLowerCase()})</option>)}
            </select>
          ) : (
            <span className="text-sm text-muted">Version {set.version}</span>
          )}
          <SetStatus status={set.status} />
          <div className="ml-auto flex flex-wrap items-center gap-2">
            {versions.length > 0 && <button className="btn-ghost" onClick={() => setDialog('diff')}><GitCompare className="h-4 w-4" /> Compare</button>}
            {editable && <button className="btn-ghost" onClick={() => setDialog('add')}><Plus className="h-4 w-4" /> Add question</button>}
            {editable && (
              <button className="btn-ghost" disabled={unflagged === 0 || bulk.isPending} onClick={() => bulk.mutate()} title="Accept every pending question that has no quality flag">
                <Check className="h-4 w-4" /> Accept all unflagged ({unflagged})
              </button>
            )}
            {editable && <button className="btn-primary" onClick={() => setDialog('finalize')}><Lock className="h-4 w-4" /> Finalize</button>}
            {set.status === 'FINAL' && canEditRole && (
              <button className="btn-primary" onClick={() => newVersion.mutate()} disabled={newVersion.isPending}>
                {newVersion.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Pencil className="h-4 w-4" />} New version to edit
              </button>
            )}
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2 text-xs" aria-label="Review progress">
          <span className="chip"><span className="h-1.5 w-1.5 rounded-full bg-ok" />{s.accepted + s.edited} accepted{s.edited > 0 ? ` (${s.edited} edited)` : ''}</span>
          <span className="chip"><span className="h-1.5 w-1.5 rounded-full bg-warn" />{s.pending} to review</span>
          <span className="chip"><span className="h-1.5 w-1.5 rounded-full bg-danger" />{s.rejected} rejected</span>
          <span className="chip">Acceptance {percent(s.acceptanceRate)}</span>
          {Object.entries(s.byFlag).map(([f, n]) => <span key={f} className="chip" title={FLAG_LABEL[f as QualityFlag]?.hint}>{FLAG_LABEL[f as QualityFlag]?.label ?? f}: {n}</span>)}
          {notice && <span role="status" className="ml-auto text-accent">{notice}</span>}
        </div>

        {generating && (
          <div className="space-y-1" aria-live="polite">
            <div className="h-1.5 overflow-hidden rounded-full bg-fg/10"><div className="h-full rounded-full bg-accent transition-all" style={{ width: `${progressPercent(set)}%` }} /></div>
            <p className="text-xs text-muted">Writing questions: {set.generatedCount} of {set.targetCount}. They appear below as they are written, and you can start reviewing now once the set is a draft.</p>
          </div>
        )}
        {set.status === 'FAILED' && <p role="alert" className="text-sm text-danger">{set.error ?? 'Generation failed.'}</p>}
        {set.status === 'FINAL' && (
          <p className="flex items-center gap-2 text-xs text-muted"><Lock className="h-3.5 w-3.5" /> This version is final and cannot change. Create a new version to edit it; the old one stays exactly as it is.</p>
        )}
        {set.status === 'DRAFT' && !canEditRole && <p className="text-xs text-muted">You have read-only access to this workspace.</p>}
      </header>

      <div className="grid min-h-0 flex-1 gap-0 lg:grid-cols-[21rem_1fr]">
        <aside className="flex min-h-0 flex-col border-b border-[var(--line)] lg:border-b-0 lg:border-r" aria-label="Questions">
          <div className="grid grid-cols-3 gap-2 p-3">
            <select className="input-glass !px-2 !py-1.5 text-xs" aria-label="Filter by status" value={filter.status} onChange={(e) => setFilter({ ...filter, status: e.target.value as ReviewFilter['status'] })}>
              <option value="ALL">Any status</option>
              {(Object.keys(REVIEW_LABEL) as ReviewStatus[]).map((r) => <option key={r} value={r}>{REVIEW_LABEL[r]}</option>)}
            </select>
            <select className="input-glass !px-2 !py-1.5 text-xs" aria-label="Filter by type" value={filter.qtype} onChange={(e) => setFilter({ ...filter, qtype: e.target.value as ReviewFilter['qtype'] })}>
              <option value="ALL">Any type</option>
              {(Object.keys(TYPE_LABEL) as QuestionType[]).map((t) => <option key={t} value={t}>{TYPE_LABEL[t]}</option>)}
            </select>
            <select className="input-glass !px-2 !py-1.5 text-xs" aria-label="Filter by flag" value={filter.flag} onChange={(e) => setFilter({ ...filter, flag: e.target.value as ReviewFilter['flag'] })}>
              <option value="ALL">Any flag</option>
              <option value="NONE">No flags</option>
              <option value="ANY">Flagged</option>
              {(Object.keys(FLAG_LABEL) as QualityFlag[]).map((f) => <option key={f} value={f}>{FLAG_LABEL[f].label}</option>)}
            </select>
          </div>
          <ul className="min-h-0 flex-1 space-y-0.5 overflow-y-auto px-2 pb-3 max-lg:max-h-64">
            {shown.length === 0 && <li className="px-3 py-6 text-center text-sm text-muted">{questions.length === 0 ? (generating ? 'The first question is being written…' : 'No questions.') : 'No question matches these filters.'}</li>}
            {shown.map((x, i) => (
              <li key={x.id}>
                <button
                  type="button"
                  onClick={() => setSelectedId(x.id)}
                  aria-current={i === index}
                  className={`block w-full rounded-xl px-3 py-2 text-left transition-colors ${i === index ? 'bg-accent/15' : 'hover:bg-[var(--hover)]'}`}
                >
                  <span className="line-clamp-2 text-[0.82rem] leading-snug">{x.question}</span>
                  <span className="mt-1 flex items-center gap-2 text-[0.7rem] text-muted">
                    <span className={`h-1.5 w-1.5 rounded-full ${REVIEW_DOT[x.reviewStatus]}`} aria-hidden />
                    {REVIEW_LABEL[x.reviewStatus]} · {TYPE_LABEL[x.qtype]}
                    {x.flags.length > 0 && <span className="text-warn">· {x.flags.length} flag{x.flags.length > 1 ? 's' : ''}</span>}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </aside>

        <section className="min-h-0 overflow-y-auto" aria-label="Question">
          {!q ? (
            <p className="p-8 text-center text-sm text-muted">{generating ? 'Questions will appear here as they are written.' : 'Nothing to review.'}</p>
          ) : (
            <div className="mx-auto max-w-3xl space-y-5 p-4 sm:p-6">
              <div className="flex flex-wrap items-center gap-2 text-xs">
                <span className="text-muted">{index + 1} of {shown.length}</span>
                <span className="chip"><span className={`h-1.5 w-1.5 rounded-full ${REVIEW_DOT[q.reviewStatus]}`} />{REVIEW_LABEL[q.reviewStatus]}</span>
                <span className="chip">{TYPE_LABEL[q.qtype]}</span>
                {q.origin !== 'SYNTHETIC' && <span className="chip">{q.origin === 'MANUAL' ? 'Added by hand' : 'From feedback'}</span>}
                {q.flags.map((f) => <span key={f} className="chip !border-warn/40 !text-warn" title={FLAG_LABEL[f].hint}>{FLAG_LABEL[f].label}</span>)}
              </div>

              {editing ? (
                <div className="space-y-3">
                  <label className="block space-y-1.5 text-sm font-medium">
                    Question
                    <textarea
                      className="input-glass min-h-[5rem]" value={draft.question} autoFocus
                      onChange={(e) => setDraft({ ...draft, question: e.target.value })}
                      onKeyDown={(e) => { if (e.key === 'Escape') setEditing(false); if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) saveEdit() }}
                    />
                  </label>
                  <label className="block space-y-1.5 text-sm font-medium">
                    Expected answer
                    <textarea
                      className="input-glass min-h-[5rem]" value={draft.expectedAnswer}
                      onChange={(e) => setDraft({ ...draft, expectedAnswer: e.target.value })}
                      onKeyDown={(e) => { if (e.key === 'Escape') setEditing(false); if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) saveEdit() }}
                    />
                  </label>
                  <label className="block space-y-1.5 text-sm font-medium">
                    Type
                    <select className="input-glass !w-auto" value={draft.qtype} onChange={(e) => setDraft({ ...draft, qtype: e.target.value as QuestionType })}>
                      {(Object.keys(TYPE_LABEL) as QuestionType[]).map((t) => <option key={t} value={t}>{TYPE_LABEL[t]}</option>)}
                    </select>
                  </label>
                  <div className="flex items-center gap-2">
                    <button className="btn-primary" onClick={saveEdit} disabled={patch.isPending || draft.question.trim().length < 3 || draft.expectedAnswer.trim().length === 0}>
                      {patch.isPending && <Loader2 className="h-4 w-4 animate-spin" />} Save changes <Kbd>Ctrl ↵</Kbd>
                    </button>
                    <button className="btn-ghost" onClick={() => setEditing(false)}>Cancel <Kbd>Esc</Kbd></button>
                  </div>
                </div>
              ) : (
                <div className="space-y-4">
                  <div>
                    <p className="eyebrow">Question</p>
                    <h2 className="mt-1 text-[1.25rem] font-semibold leading-snug">{q.question}</h2>
                  </div>
                  <div>
                    <p className="eyebrow">Expected answer</p>
                    <p className="mt-1 text-[0.97rem] leading-relaxed">{q.expectedAnswer}</p>
                  </div>
                </div>
              )}

              <div className="space-y-2">
                <p className="eyebrow">Evidence ({evidence.length})</p>
                {evidence.length === 0 ? (
                  <p className="text-sm text-muted">{q.qtype === 'UNANSWERABLE' ? 'An unanswerable question has no evidence.' : 'No evidence selected.'}</p>
                ) : (
                  <ul className="space-y-1.5">
                    {evidence.map((e, i) => {
                      const saved = q.evidence[i]
                      return (
                        <li key={`${e.documentId}-${e.charStart}-${i}`}>
                          <div className={`flex items-start gap-2 rounded-xl border p-2.5 text-sm ${i === focus ? 'border-accent/60 bg-accent/5' : 'border-[var(--line)]'}`}>
                            <button type="button" className="min-w-0 flex-1 text-left" onClick={() => setFocus(i)}>
                              <span className="block text-xs font-semibold text-accent">{titleOf(e.documentId)}</span>
                              <span className="mt-0.5 line-clamp-3 text-[0.85rem] text-muted">{editing && !(saved && saved.charStart === e.charStart && saved.charEnd === e.charEnd) ? `Characters ${e.charStart} to ${e.charEnd} (changed)` : saved?.text}</span>
                            </button>
                            {editing && evidence.length > 1 && <button className="btn-ghost !p-1.5" aria-label="Remove this evidence" onClick={() => { setSpanDraft(removeEvidence(evidence, i)); setFocus(0) }}><X className="h-3.5 w-3.5" /></button>}
                          </div>
                        </li>
                      )
                    })}
                  </ul>
                )}
              </div>

              {focusSpan && (
                <div className="space-y-2">
                  <DocumentViewer
                    documentId={focusSpan.documentId}
                    highlights={highlights}
                    scrollKey={focus}
                    className="h-72"
                    onSelect={editing ? (range, documentId) => setSelection({ range, documentId }) : undefined}
                  />
                  {editing && (
                    <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl bg-accent/10 px-3 py-2 text-xs text-accent">
                      <span>{selection ? `Selected ${selection.range.end - selection.range.start} characters` : 'Select text in the document to change the evidence'}</span>
                      <span className="flex gap-1.5">
                        <button className="btn-ghost" disabled={!selection} onClick={() => { if (selection) { setSpanDraft(replaceEvidence(evidence, focus, selection.documentId, selection.range)); setSelection(null) } }}>Replace selected evidence</button>
                        <button className="btn-ghost" disabled={!selection} onClick={() => { if (selection) { setSpanDraft(addEvidence(evidence, selection.documentId, selection.range)); setFocus(evidence.length); setSelection(null) } }}>Add as extra evidence</button>
                      </span>
                    </div>
                  )}
                </div>
              )}

              {editable && !editing && (
                <div className="sticky bottom-0 -mx-4 flex flex-wrap items-center gap-2 border-t border-[var(--line)] bg-canvas/95 px-4 py-3 sm:-mx-6 sm:px-6">
                  <button className="btn-primary !bg-ok" onClick={() => decide('ACCEPTED')} disabled={patch.isPending}><Check className="h-4 w-4" /> Accept <Kbd>A</Kbd></button>
                  <button className="btn-primary !bg-danger" onClick={() => decide('REJECTED')} disabled={patch.isPending}><X className="h-4 w-4" /> Reject <Kbd>R</Kbd></button>
                  <button className="btn-ghost" onClick={startEdit}><Pencil className="h-4 w-4" /> Edit <Kbd>E</Kbd></button>
                  {q.reviewStatus !== 'PENDING' && <button className="btn-ghost" onClick={() => decide('PENDING')}><RotateCcw className="h-4 w-4" /> Undo <Kbd>P</Kbd></button>}
                  <span className="ml-auto hidden text-xs text-muted sm:block">Next <Kbd>J</Kbd> Previous <Kbd>K</Kbd></span>
                </div>
              )}
            </div>
          )}
        </section>
      </div>

      {dialog === 'add' && <AddQuestionDialog testsetId={id} onClose={() => setDialog(null)} onAdded={(added) => {
        setDialog(null)
        setFilter(NO_FILTER)
        setSelectedId(added.id)
        void qc.invalidateQueries({ queryKey: queryKeys.testsetQuestions(id) })
        void qc.invalidateQueries({ queryKey: queryKeys.testset(id) })
      }} />}
      {dialog === 'diff' && versions.length > 0 && <DiffDialog testset={set} others={versions} onClose={() => setDialog(null)} />}
      {dialog === 'finalize' && (
        <Dialog title="Finalize this version" onClose={() => setDialog(null)}>
          <div className="space-y-4 text-sm">
            <p>
              Finalizing freezes version {set.version}: {s.accepted + s.edited} accepted question{s.accepted + s.edited === 1 ? '' : 's'} will be what experiments are measured on. To change it afterwards you create a new version.
            </p>
            {s.pending > 0 && <p className="rounded-xl bg-warn/10 px-3 py-2 text-warn">{s.pending} question{s.pending === 1 ? ' is' : 's are'} still pending. They will stay in this version but are not used for measuring.</p>}
            {finalize.isError && <p role="alert" className="text-danger">{errorText(finalize.error, 'Could not finalize.')}</p>}
            <div className="flex justify-end gap-2">
              <button className="btn-ghost" onClick={() => setDialog(null)}>Keep reviewing</button>
              <button className="btn-primary" onClick={() => finalize.mutate(s.pending > 0)} disabled={finalize.isPending}>
                {finalize.isPending && <Loader2 className="h-4 w-4 animate-spin" />} Finalize version {set.version}
              </button>
            </div>
          </div>
        </Dialog>
      )}
    </div>
  )
}
