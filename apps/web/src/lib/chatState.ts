import type { Citation, Trace, TraceSpan } from './api'

export type TurnStatus = 'streaming' | 'done' | 'error' | 'stopped'

export interface Turn {
  id: number
  question: string
  answer: string
  status: TurnStatus
  spans: TraceSpan[]
  citations: Citation[]
  unknownLabels: string[]
  error?: string
  sanitized?: boolean
  abstained?: boolean
  model?: string
  latencyMs?: number
  traceId?: string
}

export type Action =
  | { type: 'start'; id: number; question: string }
  | { type: 'event'; id: number; event: string; data: Record<string, unknown> }
  | { type: 'ended'; id: number }
  | { type: 'abort'; id: number }
  | { type: 'fail'; id: number; message: string }
  | { type: 'drop'; id: number }
  | { type: 'load'; turns: Turn[] }
  | { type: 'reset' }

function patch(state: Turn[], id: number, fn: (t: Turn) => Turn): Turn[] {
  return state.map((t) => (t.id === id ? fn(t) : t))
}

/** Pure reducer for a conversation: easy to test, and the UI just renders its output. */
export function reduce(state: Turn[], action: Action): Turn[] {
  switch (action.type) {
    case 'start':
      return [...state, { id: action.id, question: action.question, answer: '', status: 'streaming', spans: [], citations: [], unknownLabels: [] }]
    case 'event':
      return patch(state, action.id, (t) => applyEvent(t, action.event, action.data))
    case 'ended': // the stream closed; if no terminal event arrived the answer is incomplete
      return patch(state, action.id, (t) => (t.status === 'streaming' ? { ...t, status: 'error', error: 'The connection closed before the answer finished.' } : t))
    case 'abort':
      return patch(state, action.id, (t) => ({ ...t, status: 'stopped' }))
    case 'fail':
      return patch(state, action.id, (t) => ({ ...t, status: 'error', error: action.message }))
    case 'drop':
      return state.filter((t) => t.id !== action.id)
    case 'load':
      return action.turns
    case 'reset':
      return []
  }
}

function applyEvent(t: Turn, event: string, d: Record<string, unknown>): Turn {
  switch (event) {
    case 'token':
      return { ...t, answer: t.answer + String(d.t ?? '') }
    case 'trace':
      return { ...t, spans: [...t.spans, d as unknown as TraceSpan] }
    case 'citations':
      return { ...t, citations: (d.citations as Citation[]) ?? [], unknownLabels: (d.unknown as string[]) ?? [] }
    case 'done':
      // The server's final text has links and images stripped; it replaces what was streamed.
      return {
        ...t,
        status: 'done',
        answer: String(d.answer ?? t.answer),
        sanitized: Boolean(d.sanitized),
        abstained: Boolean(d.abstained),
        model: d.model as string | undefined,
        latencyMs: d.latencyMs as number | undefined,
        traceId: d.traceId as string | undefined,
      }
    case 'error':
      return { ...t, status: 'error', error: String(d.message ?? 'Something went wrong'), traceId: d.traceId as string | undefined }
    default:
      return t
  }
}

/** A finished turn rebuilt from a stored trace, so past answers can be reopened from the history list. */
export function turnFromTrace(trace: Trace, id: number): Turn {
  return {
    id,
    question: trace.question,
    answer: trace.answer ?? '',
    status: trace.status === 'ERROR' ? 'error' : trace.status === 'CANCELLED' ? 'stopped' : 'done',
    spans: trace.spans,
    citations: trace.data.citations ?? [],
    unknownLabels: trace.data.unknownCitations ?? [],
    error: trace.data.error ? 'This answer did not complete.' : undefined,
    abstained: trace.status === 'ABSTAINED',
    model: trace.model,
    latencyMs: trace.latencyMs,
    traceId: trace.id,
  }
}

export type StepState = 'done' | 'active' | 'pending'
export interface Step {
  key: string
  label: string
  state: StepState
}

/**
 * The four visible phases of an answer, derived from the trace spans that have arrived so far. Gives the user honest
 * progress ("Searching", "Reading sources", "Writing") instead of a bare spinner.
 */
export function progressSteps(turn: Pick<Turn, 'spans' | 'answer' | 'status'>): Step[] {
  const seen = new Set(turn.spans.map((s) => s.stage))
  const finished = turn.status !== 'streaming'
  const searchDone = seen.has('retrieval')
  const rankDone = seen.has('rerank')
  const readDone = seen.has('context')
  const writeDone = finished || seen.has('generation')
  const writing = turn.answer.length > 0
  const defs: [string, string, boolean][] = [
    ['search', 'Searching your documents', searchDone || finished],
    ['rank', 'Ranking passages', rankDone || finished],
    ['read', 'Reading the best sources', readDone || finished],
    ['write', 'Writing the answer', writeDone],
  ]
  let activeSet = false
  return defs.map(([key, label, done]) => {
    if (done) return { key, label, state: 'done' as const }
    if (!activeSet && (key !== 'write' || readDone || writing)) {
      activeSet = true
      return { key, label, state: 'active' as const }
    }
    return { key, label, state: 'pending' as const }
  })
}
