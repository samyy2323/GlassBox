import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { ReportView, type ReportSnapshot } from '../components/ReportView'
import { ShareDialog } from '../components/ShareDialog'
import PublicReport from './PublicReport'

vi.mock('../lib/workspace', () => ({ useWorkspace: () => ({ current: { id: 'ws-1', name: 'Test', role: 'OWNER' }, workspaces: [], loading: false, select: () => {} }) }))

const snapshot: ReportSnapshot = {
  kind: 'EXPERIMENT', title: 'Experiment: budgets', generatedAt: '2026-10-15T10:00:00Z', redacted: true,
  summary: ['30 questions from reported v1 were measured on 2 configurations.', 'roomy against tiny budget on Evidence in prompt: B beats A by +100.0 points, 95% CI [+100.0, +100.0], p=0.00'],
  testset: { name: 'reported', version: 1, questions: 30, accepted: 28, edited: 2, rejected: 4, acceptanceRate: 0.88, fromFeedback: 1, byType: { FACTUAL: 20, NUMERIC: 10 } },
  configs: [{ label: 'tiny budget', settings: ['default chunks', 'hybrid search 1.0:1.0', 'top 5', '100-token context budget'] }],
  metrics: [{ label: 'tiny budget', rows: [{ metric: 'context_pass', name: 'Evidence in prompt', value: 0, ciLow: 0, ciHigh: 0, n: 30, judged: false }, { metric: 'correctness', name: 'Correctness', value: 0.8, ciLow: 0.6, ciHigh: 0.95, n: 30, judged: true }, { metric: 'latency_p50_ms', name: 'Latency p50 (ms)', value: 15.6, n: 30, judged: false }] }],
  comparisons: [{ label: 'roomy', rows: [{ name: 'Evidence in prompt', verdict: 'B beats A by +100.0 points' }] }],
  failures: { 'tiny budget': { CONTEXT_MISS: 30 }, roomy: {} },
  limitations: ['Every interval is a 95% bootstrap over the questions measured.'],
}

describe('a report', () => {
  const html = renderToStaticMarkup(<ReportView snapshot={snapshot} />)

  it('shows the summary, the numbers with their intervals, the verdicts, the failure bars and the limitations', () => {
    expect(html).toContain('Experiment: budgets')
    expect(html).toContain('question texts redacted')
    expect(html).toContain('0% [0%, 0%]')
    expect(html).toContain('80% [60%, 95%]')
    expect(html).toContain('(judged)')
    expect(html).toContain('16 ms')
    expect(html).toContain('B beats A by +100.0 points')
    expect(html).toContain('context miss')
    expect(html).toContain('No failures.')
    expect(html).toContain('Every interval is a 95% bootstrap')
    expect(html).not.toContain('Failed questions')
  })
})

describe('the page behind a share link', () => {
  const render = (seed: (qc: QueryClient) => void) => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    seed(qc)
    return renderToStaticMarkup(<QueryClientProvider client={qc}><MemoryRouter initialEntries={['/r/tok']}><Routes><Route path="/r/:token" element={<PublicReport />} /></Routes></MemoryRouter></QueryClientProvider>)
  }

  it('shows the report read-only, with the expiry and a PDF link that needs no sign-in', () => {
    const html = render((qc) => qc.setQueryData(['public-report', 'tok'], { title: snapshot.title, snapshot, createdAt: snapshot.generatedAt, expiresAt: new Date(Date.now() + 3 * 86_400_000 + 60_000).toISOString() }))
    expect(html).toContain('Read-only report')
    expect(html).toContain('expires in 3 days')
    expect(html).toContain('href="/api/public/reports/tok/pdf"')
    expect(html).toContain('Experiment: budgets')
  })

  it('says the link is not valid when it is not', () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    qc.setQueryDefaults(['public-report', 'tok'], { retry: false })
    void qc.prefetchQuery({ queryKey: ['public-report', 'tok'], queryFn: () => Promise.reject(new Error('invalid')) }).catch(() => {})
    const html = renderToStaticMarkup(<QueryClientProvider client={qc}><MemoryRouter initialEntries={['/r/tok']}><Routes><Route path="/r/:token" element={<PublicReport />} /></Routes></MemoryRouter></QueryClientProvider>)
    expect(html).toContain('Loading the report')
  })
})

describe('the share dialog', () => {
  it('starts by offering to make the report, with the redaction choice', () => {
    const qc = new QueryClient()
    const html = renderToStaticMarkup(<QueryClientProvider client={qc}><ShareDialog kind="EXPERIMENT" refId="e1" onClose={() => {}} /></QueryClientProvider>)
    expect(html).toContain('Share a report')
    expect(html).toContain('Leave out the text of questions')
    expect(html).toContain('Make the report')
    expect(html).not.toContain('Read-only link')
  })
})
