import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { Diagnosis } from '../components/Diagnosis'
import { ParetoPlot } from '../components/ParetoPlot'
import type { Autotune, AutotuneSummary, Diagnosis as DiagnosisReport, Experiment } from '../lib/api'
import { queryKeys } from '../lib/queryKeys'
import AutotuneDetail from './AutotuneDetail'
import Autotunes from './Autotunes'
import run from './fixtures/autotune-run.json'

vi.mock('../lib/workspace', () => ({
  useWorkspace: () => ({ current: { id: 'ws-1', name: 'Test', role: 'OWNER' }, workspaces: [], loading: false, select: () => {} }),
}))

// These render the pages on the server from data that was already fetched, which is enough to catch a page that crashes on the real shape of
// the data (the fixture is a real response of the API) without a browser.

function render(ui: React.ReactElement, seed: (qc: QueryClient) => void, path = '/') {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  seed(qc)
  return renderToStaticMarkup(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

const done = run as unknown as Autotune

describe('the AutoTune run page', () => {
  const page = (a: Autotune) =>
    render(<Routes><Route path="/autotune/:id" element={<AutotuneDetail />} /></Routes>, (qc) => qc.setQueryData(queryKeys.autotune(a.id), a), `/autotune/${a.id}`)

  it('shows the verdict, both held-out scores, the finalists table, the picks and what mattered', () => {
    const html = page(done)
    expect(html).toContain('data-testid="autotune-verdict"')
    expect(html).toContain('No significant difference')
    expect(html).toContain('Finalists and the baseline')
    expect(html).toContain('Recommendations')
    expect(html).toContain('Best value')
    expect(html).toContain('What mattered')
    expect(html).toContain('Quality against cost')
    expect(html).toContain('<svg')
    expect(html).toContain('Promote the winner to workspace default') // the fixture is read by an owner
    expect(html).toContain('30 questions to tune on, 13 held out')
    expect(html).not.toMatch(/10[1-9]%/) // an axis never runs past 100%
    for (const c of done.result!.candidates) expect(html).toContain(c.label.split(' · ')[0])
  })

  it('shows the progress steps instead of a result while the search is running', () => {
    const html = page({ ...done, status: 'RUNNING', stage: 'STAGE2', result: undefined })
    expect(html).toContain('Chunking and embeddings')
    expect(html).toContain('Retrieval settings')
    expect(html).not.toContain('data-testid="autotune-verdict"')
    expect(html).not.toContain('Promote the winner')
    expect(html).toContain('searches')
  })

  it('says when the search was cut short and when the baseline won', () => {
    const capped = page({ ...done, stoppedBy: 'SPEND_CAP', result: { ...done.result!, stoppedBy: 'SPEND_CAP' } })
    expect(capped).toContain('spend cap')
    const baselineWon = page({ ...done, result: { ...done.result!, headline: { ...done.result!.headline, winnerIsBaseline: true, verdict: 'The baseline itself came out on top on the tuning questions; nothing was found that beats it.' } } })
    expect(baselineWon).toContain('The baseline itself came out on top')
  })

  it('shows a failed run’s error', () => {
    expect(page({ ...done, status: 'FAILED', result: undefined, error: 'Stopped: boom' })).toContain('Stopped: boom')
  })
})

describe('the AutoTune list', () => {
  const list = (items: AutotuneSummary[]) => render(<Autotunes />, (qc) => qc.setQueryData(queryKeys.autotunes('ws-1'), items))

  it('invites the first run when there is none', () => {
    expect(list([])).toContain('No AutoTune runs yet')
  })

  it('lists runs with their status and headline', () => {
    const html = list([{ id: 'a1', name: 'First search', testsetName: 'Site handbooks', objective: 'BEST_QUALITY', status: 'DONE', stage: 'DONE', createdAt: '2026-10-15T04:00:00Z', headline: 'B beats A by +4.0 points' }])
    expect(html).toContain('First search')
    expect(html).toContain('B beats A by +4.0 points')
    expect(html).toContain('Done')
  })
})

describe('the quality against cost plot', () => {
  it('draws every point and names the picks', () => {
    const html = renderToStaticMarkup(
      <ParetoPlot picks={{ bestQuality: 'a', fastest: 'b' }} points={[
        { key: 'a', label: 'A', quality: 0.9, cost: 800, latencyMs: 40, frontier: true, role: 'WINNER' },
        { key: 'b', label: 'B', quality: 0.8, cost: 300, latencyMs: 10, frontier: true, role: 'BASELINE' },
        { key: 'c', label: 'C', quality: 0.5, cost: 900, latencyMs: 90, frontier: false, role: 'FINALIST' },
      ]} />,
    )
    expect(html.match(/<circle/g)).toHaveLength(3)
    expect(html).toContain('best quality')
    expect(html).toContain('fastest')
    expect(html).toContain('<path')
  })

  it('says so when there is nothing to draw', () => {
    expect(renderToStaticMarkup(<ParetoPlot points={[]} picks={{}} />)).toContain('Nothing was measured')
  })
})

describe('the failure report', () => {
  const experiment = {
    id: 'e1', testsetId: 't1', mode: 'RETRIEVAL', status: 'DONE', runs: [{ id: 'r1', label: 'Vector', status: 'DONE', ordinal: 0, samplesDone: 10, config: {}, metrics: [] }],
  } as unknown as Experiment
  const report: DiagnosisReport = {
    experimentId: 'e1', runId: 'r1', runLabel: 'Vector', mode: 'RETRIEVAL', questions: 10, failed: 5,
    config: { retrieval: { mode: 'VECTOR', model: 'bge-small-en-v1.5', rrfK: 60, wVec: 1, wKw: 1, kFused: 50 }, reranker: 'NONE', topN: 5, tokenBudget: 3000 },
    byStage: { RANKING_MISS: 3, RETRIEVAL_MISS: 2 },
    byStageAndType: { RANKING_MISS: { FACTUAL: 2, NUMERIC: 1 }, RETRIEVAL_MISS: { FACTUAL: 2 } },
    bySub: {},
    recommendations: [{ stage: 'RANKING_MISS', count: 3, percent: 60, text: '60% of failures are ranking misses, so add a reranker.', change: { reranker: 'CROSS_ENCODER' }, changeLabel: 'Add the cross-encoder reranker' }],
    failures: [{ questionId: 'q1', question: 'Which pump is inspected first?', qtype: 'FACTUAL', stage: 'RANKING_MISS' }],
  }
  const html = (canEdit: boolean, d: DiagnosisReport = report) => render(<Diagnosis e={experiment} canEdit={canEdit} />, (qc) => qc.setQueryData(queryKeys.diagnosis('e1', 'r1'), d))

  it('shows the bars, the matrix and the advice with a button to try it', () => {
    const h = html(true)
    expect(h).toContain('5 of 10')
    expect(h).toContain('Failures by stage')
    expect(h).toContain('Stage by question type')
    expect(h).toContain('60% of failures are ranking misses, so add a reranker.')
    expect(h).toContain('Add the cross-encoder reranker')
    expect(h).toContain('Which pump is inspected first?')
  })

  it('leaves out the button for a viewer and says when nothing failed', () => {
    expect(html(false)).not.toContain('Add the cross-encoder reranker</button>')
    expect(html(true, { ...report, failed: 0, byStage: {}, byStageAndType: {}, recommendations: [], failures: [] })).toContain('No failures')
  })
})
