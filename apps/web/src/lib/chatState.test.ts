import { describe, expect, it } from 'vitest'
import type { Trace } from './api'
import { progressSteps, reduce, turnFromTrace, type Turn } from './chatState'

const start = (id = 1, q = 'why?') => reduce([], { type: 'start', id, question: q })
const ev = (s: Turn[], event: string, data: Record<string, unknown>, id = 1) => reduce(s, { type: 'event', id, event, data })

describe('reduce', () => {
  it('accumulates tokens, spans and citations, then the server final text replaces the streamed text', () => {
    let s = start()
    s = ev(s, 'trace', { stage: 'retrieval', millis: 5 })
    s = ev(s, 'token', { t: 'Hello ' })
    s = ev(s, 'token', { t: '![x](http://evil)' })
    s = ev(s, 'citations', { citations: [{ label: 'c1' }], unknown: ['c9'] })
    s = ev(s, 'done', { answer: 'Hello ', sanitized: true, abstained: false, model: 'm', latencyMs: 1234, traceId: 't1' })
    expect(s[0]).toMatchObject({ status: 'done', answer: 'Hello ', sanitized: true, model: 'm', latencyMs: 1234, traceId: 't1', unknownLabels: ['c9'] })
    expect(s[0].spans).toHaveLength(1)
    expect(s[0].citations).toHaveLength(1)
  })

  it('records an error event with its trace id', () => {
    const s = ev(start(), 'error', { message: 'model down', traceId: 't2' })
    expect(s[0]).toMatchObject({ status: 'error', error: 'model down', traceId: 't2' })
  })

  it('turns a stream that closes without a terminal event into an error, but leaves finished turns alone', () => {
    expect(reduce(start(), { type: 'ended', id: 1 })[0]).toMatchObject({ status: 'error' })
    const done = ev(start(), 'done', { answer: 'x' })
    expect(reduce(done, { type: 'ended', id: 1 })[0].status).toBe('done')
  })

  it('stops, fails, drops and resets', () => {
    expect(reduce(start(), { type: 'abort', id: 1 })[0].status).toBe('stopped')
    expect(reduce(start(), { type: 'fail', id: 1, message: 'nope' })[0]).toMatchObject({ status: 'error', error: 'nope' })
    expect(reduce(start(), { type: 'drop', id: 1 })).toEqual([])
    expect(reduce(start(), { type: 'reset' })).toEqual([])
  })

  it('only touches the turn it is addressed to', () => {
    let s = start(1, 'a')
    s = reduce(s, { type: 'start', id: 2, question: 'b' })
    s = ev(s, 'token', { t: 'x' }, 2)
    expect(s.map((t) => t.answer)).toEqual(['', 'x'])
  })
})

describe('progressSteps', () => {
  const steps = (spans: string[], answer = '', status: Turn['status'] = 'streaming') =>
    progressSteps({ spans: spans.map((stage) => ({ stage, millis: 1 })), answer, status }).map((s) => s.state)

  it('starts with searching active', () => {
    expect(steps([])).toEqual(['active', 'pending', 'pending', 'pending'])
  })

  it('advances as spans arrive', () => {
    expect(steps(['query_embedding', 'retrieval'])).toEqual(['done', 'active', 'pending', 'pending'])
    expect(steps(['retrieval', 'rerank'])).toEqual(['done', 'done', 'active', 'pending'])
    expect(steps(['retrieval', 'rerank', 'context'])).toEqual(['done', 'done', 'done', 'active'])
  })

  it('marks everything done when the answer is finished', () => {
    expect(steps([], 'text', 'done')).toEqual(['done', 'done', 'done', 'done'])
  })
})

describe('turnFromTrace', () => {
  const trace = (status: Trace['status']): Trace => ({
    id: 'tr1', kind: 'CHAT', status, question: 'q', answer: 'a [c1]', model: 'llama', latencyMs: 900, createdAt: '2026-10-14T10:00:00Z',
    spans: [{ stage: 'retrieval', millis: 3 }],
    data: { candidates: [], citations: [{ label: 'c1', chunkId: 'c', documentId: 'd', title: 'Doc', charStart: 0, charEnd: 5, text: 'x' }], unknownCitations: [] },
  })

  it('rebuilds a finished turn with its citations and a link back to the trace', () => {
    expect(turnFromTrace(trace('OK'), 7)).toMatchObject({ id: 7, status: 'done', answer: 'a [c1]', traceId: 'tr1', model: 'llama', abstained: false })
    expect(turnFromTrace(trace('OK'), 7).citations).toHaveLength(1)
  })

  it('maps statuses', () => {
    expect(turnFromTrace(trace('ABSTAINED'), 1)).toMatchObject({ status: 'done', abstained: true })
    expect(turnFromTrace(trace('ERROR'), 1).status).toBe('error')
    expect(turnFromTrace(trace('CANCELLED'), 1).status).toBe('stopped')
  })
})
