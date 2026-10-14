import { useMemo, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Loader2, Plus, X } from 'lucide-react'
import { api, ApiError, type QuestionType, type SpanInput, type TestQuestion } from '../lib/api'
import type { Highlight } from '../lib/highlight'
import { queryKeys } from '../lib/queryKeys'
import type { Range } from '../lib/selection'
import { TYPE_LABEL } from '../lib/testsets'
import { useWorkspace } from '../lib/workspace'
import { Dialog } from './Dialog'
import { DocumentViewer } from './DocumentViewer'

const TYPES = Object.keys(TYPE_LABEL) as QuestionType[]

/** Adds a question by hand: pick a document, select the passage that answers it (that selection is the gold evidence), type both texts. */
export function AddQuestionDialog({ testsetId, onClose, onAdded }: { testsetId: string; onClose: () => void; onAdded: (q: TestQuestion) => void }) {
  const { current } = useWorkspace()
  const docs = useQuery({ queryKey: queryKeys.documents(current?.id), queryFn: () => api.documents(), enabled: !!current })
  const ready = useMemo(() => (docs.data?.items ?? []).filter((d) => d.status === 'READY'), [docs.data])

  const [documentId, setDocumentId] = useState<string>('')
  const [spans, setSpans] = useState<SpanInput[]>([])
  const [pending, setPending] = useState<Range | null>(null)
  const [question, setQuestion] = useState('')
  const [answer, setAnswer] = useState('')
  const [qtype, setQtype] = useState<QuestionType>('FACTUAL')

  const activeDoc = documentId || ready[0]?.id || ''
  const content = useQuery({ queryKey: queryKeys.documentContent(activeDoc), queryFn: () => api.documentContent(activeDoc), enabled: !!activeDoc, staleTime: 5 * 60_000 })
  const text = content.data?.text ?? ''
  const titleOf = (id: string) => ready.find((d) => d.id === id)?.title ?? 'document'

  const highlights: Highlight[] = [
    ...spans.filter((s) => s.documentId === activeDoc).map((s) => ({ start: s.charStart, end: s.charEnd, kind: 'primary' as const, label: 'evidence' })),
    ...(pending ? [{ start: pending.start, end: pending.end, kind: 'secondary' as const, label: 'selection' }] : []),
  ]

  const add = useMutation({
    mutationFn: () => api.addQuestion(testsetId, { question: question.trim(), expectedAnswer: answer.trim(), qtype, spans }),
    onSuccess: onAdded,
  })
  const needsEvidence = qtype !== 'UNANSWERABLE'
  const valid = question.trim().length >= 8 && answer.trim().length > 0 && (!needsEvidence || spans.length > 0)
  const error = add.error instanceof ApiError ? add.error.message : add.error ? 'Could not add the question.' : null

  return (
    <Dialog title="Add a question" onClose={onClose} wide>
      <div className="grid gap-5 lg:grid-cols-2">
        <div className="space-y-3">
          <label className="block space-y-1.5 text-sm font-medium">
            Document
            <select className="input-glass" value={activeDoc} onChange={(e) => { setDocumentId(e.target.value); setPending(null) }}>
              {ready.map((d) => <option key={d.id} value={d.id}>{d.title}</option>)}
            </select>
          </label>
          {activeDoc ? (
            <DocumentViewer documentId={activeDoc} highlights={highlights} className="h-72" onSelect={(r) => setPending(r)} />
          ) : (
            <p className="text-sm text-muted">No document is ready yet.</p>
          )}
          <div className="flex items-center justify-between gap-3 text-xs text-muted">
            <span>{pending ? `Selected ${pending.end - pending.start} characters` : 'Select the passage that answers your question'}</span>
            <button
              type="button"
              className="btn-ghost"
              disabled={!pending}
              onClick={() => {
                if (!pending) return
                setSpans([...spans, { documentId: activeDoc, charStart: pending.start, charEnd: pending.end }])
                setPending(null)
                window.getSelection()?.removeAllRanges()
              }}
            >
              <Plus className="h-3.5 w-3.5" /> Use as evidence
            </button>
          </div>
        </div>

        <form
          className="space-y-3"
          onSubmit={(e) => {
            e.preventDefault()
            if (valid && !add.isPending) add.mutate()
          }}
        >
          <label className="block space-y-1.5 text-sm font-medium">
            Question
            <textarea className="input-glass min-h-[4.5rem]" value={question} onChange={(e) => setQuestion(e.target.value)} placeholder="A question someone could ask without seeing the document" />
          </label>
          <label className="block space-y-1.5 text-sm font-medium">
            Expected answer
            <textarea className="input-glass min-h-[4.5rem]" value={answer} onChange={(e) => setAnswer(e.target.value)} />
          </label>
          <label className="block space-y-1.5 text-sm font-medium">
            Type
            <select className="input-glass" value={qtype} onChange={(e) => setQtype(e.target.value as QuestionType)}>
              {TYPES.map((t) => <option key={t} value={t}>{TYPE_LABEL[t]}</option>)}
            </select>
          </label>
          <div className="space-y-1.5">
            <p className="text-sm font-medium">Evidence ({spans.length})</p>
            {spans.length === 0 ? (
              <p className="text-xs text-muted">{needsEvidence ? 'Select text in the document and add it here.' : 'An unanswerable question has no evidence.'}</p>
            ) : (
              <ul className="space-y-1.5">
                {spans.map((s, i) => (
                  <li key={i} className="flex items-start gap-2 rounded-lg border border-[var(--line)] p-2 text-xs">
                    <span className="min-w-0 flex-1">
                      <span className="block font-semibold text-accent">{titleOf(s.documentId)}</span>
                      <span className="line-clamp-2 text-muted">{s.documentId === activeDoc && text ? text.slice(s.charStart, s.charEnd) : `characters ${s.charStart} to ${s.charEnd}`}</span>
                    </span>
                    <button type="button" className="btn-ghost !p-1" aria-label="Remove evidence" onClick={() => setSpans(spans.filter((_, k) => k !== i))}><X className="h-3.5 w-3.5" /></button>
                  </li>
                ))}
              </ul>
            )}
          </div>
          {error && <p role="alert" className="text-sm text-red-500">{error}</p>}
          <div className="flex justify-end gap-2 pt-1">
            <button type="button" className="btn-ghost" onClick={onClose}>Cancel</button>
            <button type="submit" className="btn-primary" disabled={!valid || add.isPending}>
              {add.isPending && <Loader2 className="h-4 w-4 animate-spin" />} Add question
            </button>
          </div>
        </form>
      </div>
    </Dialog>
  )
}
