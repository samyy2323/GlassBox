import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { DocumentImpact, DocumentVersion } from '../lib/api'
import { queryKeys } from '../lib/queryKeys'
import DocumentChanges from './DocumentChanges'
import Feedback from './Feedback'

vi.mock('../lib/workspace', () => ({
  useWorkspace: () => ({ current: { id: 'ws-1', name: 'Test', role: 'OWNER' }, workspaces: [], loading: false, select: () => {} }),
}))

function render(path: string, route: string, element: React.ReactElement, seed: (qc: QueryClient) => void) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  seed(qc)
  return renderToStaticMarkup(<QueryClientProvider client={qc}><MemoryRouter initialEntries={[path]}><Routes><Route path={route} element={element} /></Routes></MemoryRouter></QueryClientProvider>)
}

const versions: DocumentVersion[] = [
  { version: 2, contentHash: 'b', createdAt: '2026-10-15T10:00:00Z', current: true, characters: 900 },
  { version: 1, contentHash: 'a', createdAt: '2026-10-14T10:00:00Z', current: false, characters: 1500 },
]
const impact: DocumentImpact = {
  version: 2, status: 'DONE', relocated: 3, stale: 1, testsetId: 't2', testsetVersion: 2, experimentId: 'e1', experimentStatus: 'DONE',
  staleQuestions: [{ questionId: 'q1', question: 'What is the evacuation target time?' }],
  verdict: { before: 1, after: 0.8, delta: -0.2, ciLow: -0.4, ciHigh: 0, pAdjusted: 0.12, text: 'No significant difference (CI [-40.0, +0.0], p=0.12)', questions: 5 },
  passToFail: [{ questionId: 'q2', question: 'How often are pumps inspected?', failureStage: 'RETRIEVAL_MISS' }], failToPass: 0,
  diff: { from: 1, to: 2, summary: { same: 2, added: 1, removed: 3 }, ops: [{ kind: 'SAME', text: 'Intro' }, { kind: 'REMOVED', text: 'Pumps are inspected every 14 days.' }, { kind: 'ADDED', text: 'Pumps are inspected every 7 days.' }] },
}

describe('the document changes page', () => {
  const page = (i: DocumentImpact) => render('/documents/d1/changes', '/documents/:id/changes', <DocumentChanges />, (qc) => {
    qc.setQueryData(queryKeys.documentVersions('d1'), versions)
    qc.setQueryData(queryKeys.documentImpact('d1', 2), i)
  })

  it('shows what was re-located, what went stale, the verdict, the questions that now fail and the paragraph diff', () => {
    const html = page(impact)
    expect(html).toContain('<span class="font-semibold">3</span>')
    expect(html).toContain('marked stale')
    expect(html).toContain('data-testid="impact-verdict"')
    expect(html).toContain('No significant difference')
    expect(html).toContain('How often are pumps inspected?')
    expect(html).toContain('lost at retrieval miss')
    expect(html).toContain('+ Pumps are inspected every 7 days.')
    expect(html).toContain('- Pumps are inspected every 14 days.')
    expect(html).toContain('v1 to v2')
  })

  it('explains a skipped or empty impact instead of showing numbers', () => {
    const html = page({ ...impact, status: 'NO_QUESTIONS', note: 'No final test set has a question about this document.', verdict: undefined, passToFail: [] })
    expect(html).toContain('No final test set has a question about this document.')
    expect(html).not.toContain('marked stale')
  })
})

describe('the feedback inbox', () => {
  it('asks to be an editor to a viewer and shows the empty inbox to an editor', () => {
    const empty = render('/feedback', '/feedback', <Feedback />, (qc) => {
      qc.setQueryData([...queryKeys.feedbackList('ws-1'), 'OPEN'], [])
      qc.setQueryData(queryKeys.feedbackSummary('ws-1'), { open: 0, converted: 2, dismissed: 1, down: 3, up: 0, questionsFromFeedback: 2, questionsTotal: 10 })
    })
    expect(empty).toContain('Nothing here')
    expect(empty).toContain('2 of 10 questions in your latest test sets (20%) came from real failures.')
  })
})
