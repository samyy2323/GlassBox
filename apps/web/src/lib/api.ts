// The access token lives in memory only. The refresh token is an httpOnly cookie the page never sees.
import { SseParser, type SseEvent } from './sse'

let accessToken: string | null = null
let workspaceId: string | null = null
let authLostHandler: (() => void) | null = null

export const setAccessToken = (t: string | null) => {
  accessToken = t
}
export const setWorkspaceId = (id: string | null) => {
  workspaceId = id
}
/** Called when the session can no longer be refreshed (the app then shows the login page). */
export const onAuthLost = (fn: (() => void) | null) => {
  authLostHandler = fn
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public retryAfterSeconds?: number,
  ) {
    super(message)
  }
}

export interface User {
  id: string
  email: string
}
export interface AuthResponse {
  user: User
  accessToken: string
  expiresIn: number
}
export type Role = 'OWNER' | 'EDITOR' | 'VIEWER'
export interface Workspace {
  id: string
  name: string
  role: Role
}
export type DocStatus = 'UPLOADED' | 'PROCESSING' | 'READY' | 'FAILED' | 'FAILED_UNSUPPORTED'
export interface DocumentDto {
  id: string
  title: string
  sourceType: string
  status: DocStatus
  error?: string
  version: number
  sizeBytes: number
  createdAt: string
}
export interface DocumentPage {
  items: DocumentDto[]
  nextCursor?: string
}
export interface UploadResult {
  document: DocumentDto
  duplicate: boolean
}
export interface Citation {
  label: string
  chunkId: string
  documentId: string
  title: string
  page?: number
  charStart: number
  charEnd: number
  text: string
}
export interface TraceSpan {
  stage: string
  millis: number
  detail?: Record<string, unknown>
}

export interface DocumentSection {
  ordinal: number
  parentOrdinal?: number
  level: number
  headingPath: string[]
  start: number
  end: number
}
export interface DocumentContent {
  document: DocumentDto
  /** Absent while the document is still being ingested. */
  text?: string
  pageStarts: number[]
  sections: DocumentSection[]
}

export type TraceStatus = 'OK' | 'ABSTAINED' | 'ERROR' | 'CANCELLED'
export type Disposition = 'IN_CONTEXT' | 'DROPPED' | 'CUT'
export interface CandidateView {
  chunkId: string
  documentId: string
  title: string
  page?: number
  charStart: number
  charEnd: number
  tokenCount: number
  snippet: string
  fusedScore: number
  vecRank?: number
  vecDistance?: number
  kwRank?: number
  kwScore?: number
  rerankScore?: number
  rankedPosition?: number
  disposition: Disposition
  label?: string
  reason?: string
}
export interface TraceData {
  config?: {
    retrieval: { mode: string; model: string; kVec: number; kKw: number; rrfK: number; wVec: number; wKw: number; kFused: number; keywordMatch: string }
    reranker: string
    rerankCandidates: number
    topN: number
    tokenBudget: number
  }
  candidates: CandidateView[]
  context?: {
    budget: number
    tokensUsed: number
    included: { label: string; chunkId: string; tokenCount: number }[]
    dropped: { chunkId: string; reason: string; detail: string }[]
  }
  prompt?: { role: string; content: string }[]
  citations: Citation[]
  unknownCitations: string[]
  usage?: { promptTokens: number; answerTokens: number }
  error?: string
}
export interface Trace {
  id: string
  kind: string
  status: TraceStatus
  question: string
  answer?: string
  model?: string
  latencyMs?: number
  spans: TraceSpan[]
  data: TraceData
  createdAt: string
}
export interface TraceSummary {
  id: string
  kind: string
  status: TraceStatus
  question: string
  model?: string
  latencyMs?: number
  createdAt: string
}
export interface TracePage {
  items: TraceSummary[]
  nextCursor?: string
}

export type QuestionType = 'FACTUAL' | 'NUMERIC' | 'MULTI_HOP' | 'COMPARISON' | 'UNANSWERABLE'
export type ReviewStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'EDITED'
export type TestSetStatus = 'GENERATING' | 'DRAFT' | 'FINAL' | 'FAILED'
export type QualityFlag = 'COPY_RISK' | 'AMBIGUOUS' | 'LOW_ANSWERABILITY' | 'DUPLICATE' | 'ANSWERABLE_RISK' | 'STALE'

export interface TestSetStats {
  total: number
  pending: number
  accepted: number
  edited: number
  rejected: number
  /** (accepted + edited) / reviewed; absent until something has been reviewed. */
  acceptanceRate?: number
  byType: Record<string, number>
  byFlag: Record<string, number>
  fromFeedback?: number
}
export interface TestSet {
  id: string
  name: string
  version: number
  parentVersionId?: string
  status: TestSetStatus
  targetCount: number
  generatedCount: number
  error?: string
  createdAt: string
  finalizedAt?: string
  stats: TestSetStats
}
export interface Evidence {
  documentId: string
  title: string
  charStart: number
  charEnd: number
  text: string
}
export interface TestQuestion {
  id: string
  stableId: string
  testsetId: string
  question: string
  expectedAnswer: string
  qtype: QuestionType
  origin: 'SYNTHETIC' | 'MANUAL' | 'FEEDBACK'
  reviewStatus: ReviewStatus
  flags: QualityFlag[]
  evidence: Evidence[]
  createdAt: string
}
export interface SpanInput {
  documentId: string
  charStart: number
  charEnd: number
}
export interface GenerateInput {
  name: string
  count: number
  mix?: Record<string, number>
  documentIds?: string[]
}
export interface QuestionPatch {
  question?: string
  expectedAnswer?: string
  reviewStatus?: 'ACCEPTED' | 'REJECTED' | 'PENDING'
  spans?: SpanInput[]
  qtype?: QuestionType
}
export interface TestSetDiff {
  testset: string
  against: string
  added: TestQuestion[]
  removed: TestQuestion[]
  edited: { before: TestQuestion; after: TestQuestion }[]
  unchanged: number
}

export type ExperimentStatus = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELLED' | 'ABORTED'
export type ExperimentMode = 'RETRIEVAL' | 'FULL'
export type Outcome = 'B_BETTER' | 'A_BETTER' | 'NO_DIFFERENCE'
export type JudgeStatus = 'calibrated' | 'uncalibrated' | 'unknown'

export interface ConfigInput {
  name: string
  mode?: 'VECTOR' | 'KEYWORD' | 'HYBRID'
  kFused?: number
  rrfK?: number
  wVec?: number
  wKw?: number
  keywordMatch?: 'ANY' | 'ALL'
  reranker?: 'NONE' | 'CROSS_ENCODER'
  topN?: number
  tokenBudget?: number
  chunking?: Chunking
  embeddingModel?: string
  transformer?: Transformer
}
export type Transformer = 'NONE' | 'MULTI_QUERY' | 'HYDE' | 'DECOMPOSE'
export interface Chunking {
  strategy: 'fixed' | 'recursive' | 'structure' | 'parent_child'
  size: number
  overlapPct: number
}
export interface PipelineConfig {
  retrieval: { mode: string; model?: string; rrfK: number; wVec: number; wKw: number; kFused: number }
  reranker: string
  topN: number
  tokenBudget: number
  chunking?: Chunking
  transformer?: Transformer
}
export interface MetricValue {
  metric: string
  slice: string
  method: 'question' | 'cluster'
  value?: number
  ciLow?: number
  ciHigh?: number
  n: number
}
export interface RunSummary {
  id: string
  label: string
  ordinal: number
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELLED'
  samplesDone: number
  config: PipelineConfig
  metrics: MetricValue[]
}
export interface ComparisonRow {
  metric: string
  a: number
  b: number
  delta: number
  ciLow: number
  ciHigh: number
  p: number
  pAdjusted: number
  test: 'mcnemar' | 'permutation'
  outcome: Outcome
  verdict: string
  minDetectable?: number
  n: number
}
export interface RunComparison {
  runId: string
  label: string
  rows: ComparisonRow[]
}
export interface JudgeMetric {
  status: JudgeStatus
  kappa?: number
  n?: number
}
export interface JudgeInfo {
  model: string
  rubricVersion: string
  generationModel: string
  nliModel: string
  sameModelAsGenerator: boolean
  metrics: Record<string, JudgeMetric>
  warnings: string[]
}
export interface Experiment {
  id: string
  testsetId: string
  testsetName: string
  testsetVersion: number
  name: string
  status: ExperimentStatus
  kind: string
  mode: ExperimentMode
  questionCount: number
  staleCount: number
  estimatedCost: Record<string, number>
  actualCost: Record<string, number>
  error?: string
  createdAt: string
  finishedAt?: string
  runs: RunSummary[]
  vsBaseline: RunComparison[]
  judge?: JudgeInfo
}
export interface Estimate {
  questions: number
  staleQuestions: number
  runs: number
  retrievalCalls: number
  llmCalls: number
  estimatedTokens: number
  estimatedUsd: number
  notes: string[]
  breakdown?: Record<string, unknown>
  needsConfirmation?: boolean
}
export interface ExperimentResult {
  questionId: string
  question: string
  qtype: string
  metrics: Record<string, number>
  latencyMs?: number
  failureStage?: string
  answer?: string
  traceId?: string
}
export interface Flip {
  questionId: string
  question: string
  qtype: string
  a: number
  b: number
  traceA?: string
  traceB?: string
}
export interface RunDiff {
  runA: string
  runB: string
  labelA: string
  labelB: string
  questions: number
  rows: ComparisonRow[]
  flipMetric: string
  passToFail: Flip[]
  failToPass: Flip[]
}
export type FailureStage = 'CHUNKING_MISS' | 'RETRIEVAL_MISS' | 'RANKING_MISS' | 'CONTEXT_MISS' | 'PARTIAL_RETRIEVAL' | 'GENERATION_MISS' | 'HALLUCINATED_ANSWER' | 'BAD_TEST_QUESTION'
export type FailureSub = 'UNFAITHFUL' | 'INCOMPLETE' | 'WRONG_REASONING' | 'FALSE_ABSTENTION'
export interface Recommendation {
  stage: FailureStage
  sub?: FailureSub
  count: number
  percent: number
  text: string
  /** The one setting to change, in the shape of an experiment configuration; absent when there is no one-click change. */
  change?: Partial<ConfigInput>
  changeLabel?: string
}
export interface Diagnosis {
  experimentId: string
  runId: string
  runLabel: string
  mode: ExperimentMode
  config: PipelineConfig
  questions: number
  failed: number
  byStage: Record<string, number>
  byStageAndType: Record<string, Record<string, number>>
  bySub: Record<string, number>
  recommendations: Recommendation[]
  failures: { questionId: string; question: string; qtype: string; stage: FailureStage; sub?: FailureSub; lostAt?: FailureStage; traceId?: string }[]
}

export type AutotuneStatus = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELLED'
export type AutotuneObjective = 'BEST_QUALITY' | 'CHEAPEST_ABOVE_FLOOR' | 'FASTEST_WITHIN_CAP'
export interface AutotuneRequest {
  testsetId: string
  name?: string
  objective?: AutotuneObjective
  qualityFloor?: number
  latencyCapMs?: number
  fullStage?: boolean
  stage1Candidates?: number
  stage2Candidates?: number
  finalists?: number
  fullQuestions?: number
  capMinutes?: number
  capLlmCalls?: number
  capSearches?: number
  transformers?: boolean
  confirm?: boolean
}
export interface AutotuneEstimate {
  tuningQuestions: number
  heldOutQuestions: number
  stage1Candidates: number
  stage2Candidates: number
  finalists: number
  retrievalCalls: number
  llmCalls: number
  notes: string[]
  estimatedUsd?: number
  needsConfirmation?: boolean
}
export interface AutotuneHeldOut {
  quality: number
  ciLow: number
  ciHigh: number
  n: number
  contextRecall?: number
  correctness?: number
  faithfulness?: number
  promptTokens: number
  latencyMs: number
}
export interface AutotuneVsBaseline {
  delta: number
  ciLow: number
  ciHigh: number
  p: number
  pAdjusted: number
  outcome: Outcome
  verdict: string
  minDetectable?: number
}
export interface AutotuneCandidate {
  key: string
  label: string
  config: PipelineConfig
  settings: Record<string, string>
  role: 'BASELINE' | 'FINALIST' | 'WINNER'
  heldOutRunId: string
  tuningRunId?: string
  tuning?: { quality: number; n: number }
  heldOut: AutotuneHeldOut
  vsBaseline?: AutotuneVsBaseline
  onFrontier: boolean
}
export interface AutotuneEffect {
  stage: 'STAGE1' | 'STAGE2'
  dimension: string
  value: string
  averageQuality: number
  n: number
}
export interface AutotuneResult {
  headline: {
    mode: ExperimentMode
    qualityDefinition: string
    winnerKey: string
    winnerLabel: string
    winnerConfig: PipelineConfig
    baselineLabel: string
    winnerIsBaseline: boolean
    tuning?: { quality: number; n: number }
    heldOut: AutotuneHeldOut
    baselineHeldOut: AutotuneHeldOut
    verdict: string
    vsBaseline?: AutotuneVsBaseline
    heldOutQuestions: number
    tuningQuestions: number
    shrinkage?: number
  }
  candidates: AutotuneCandidate[]
  frontier: string[]
  recommendations: { bestQuality?: string; bestValue?: string; fastest?: string }
  effects: AutotuneEffect[]
  stages: { stage1: { candidates: number; rounds: { sample: number; entered: number; kept: number }[] }; stage2: { candidates: number; rounds: { sample: number; entered: number; kept: number }[] } }
  stoppedBy?: string | null
}
export interface Autotune {
  id: string
  testsetId: string
  testsetName: string
  name: string
  objective: AutotuneObjective
  status: AutotuneStatus
  stage: string
  options: { objective: AutotuneObjective; qualityFloor: number; latencyCapMs: number; fullStage: boolean; stage1Candidates: number; stage2Candidates: number; finalists: number; fullQuestions: number; capMinutes: number; capLlmCalls: number; capSearches: number; seed: number }
  split: Record<string, number>
  spent: Record<string, number>
  stoppedBy?: string
  error?: string
  experimentId?: string
  result?: AutotuneResult
  candidates: Record<string, number>
  createdAt: string
  finishedAt?: string
}
export interface AutotuneSummary {
  id: string
  name: string
  testsetName: string
  objective: AutotuneObjective
  status: AutotuneStatus
  stage: string
  createdAt: string
  finishedAt?: string
  headline?: string
}
export interface FeedbackItem {
  id: string
  traceId?: string
  question: string
  answer?: string
  rating: 'UP' | 'DOWN'
  comment?: string
  status: 'OPEN' | 'CONVERTED' | 'DISMISSED'
  convertedQuestionId?: string
  createdAt: string
}
export interface FeedbackPassage {
  documentId: string
  title: string
  page?: number
  charStart: number
  charEnd: number
  snippet: string
  disposition: 'IN_CONTEXT' | 'DROPPED' | 'CUT'
  rank?: number
}
export interface FeedbackDetail {
  item: FeedbackItem
  traceAvailable: boolean
  warnings: string[]
  retrieved: FeedbackPassage[]
}
export interface FeedbackDiagnosed {
  stage?: string
  lostAt?: string
  retrievalProblem: boolean
  message: string
  warnings: string[]
}
export interface FeedbackSummary {
  open: number
  converted: number
  dismissed: number
  down: number
  up: number
  questionsFromFeedback: number
  questionsTotal: number
}
export interface DocumentVersion {
  version: number
  contentHash: string
  createdAt: string
  current: boolean
  characters: number
}
export interface DocumentDiff {
  from: number
  to: number
  summary: { same: number; added: number; removed: number }
  ops: { kind: 'SAME' | 'ADDED' | 'REMOVED'; text: string }[]
}
export interface DocumentImpact {
  version: number
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'NO_QUESTIONS' | 'SKIPPED' | 'FAILED'
  note?: string
  testsetId?: string
  testsetVersion?: number
  experimentId?: string
  experimentStatus?: string
  relocated: number
  stale: number
  staleQuestions: { questionId: string; question: string }[]
  verdict?: { before: number; after: number; delta: number; ciLow: number; ciHigh: number; pAdjusted: number; text: string; questions: number }
  passToFail: { questionId: string; question: string; failureStage?: string }[]
  failToPass: number
  diff?: DocumentDiff
}
export interface ReportMeta {
  id: string
  kind: 'EXPERIMENT' | 'AUTOTUNE'
  refId: string
  title: string
  redacted: boolean
  createdAt: string
  activeLinks: number
}
export interface ReportLink {
  id: string
  expiresAt: string
  revokedAt?: string
  createdAt: string
  active: boolean
}
export interface CreatedReportLink {
  id: string
  token: string
  expiresAt: string
  path: string
}
export interface ModelPrice {
  model: string
  promptUsdPerMillion: number
  completionUsdPerMillion: number
}
export interface BudgetLimits {
  monthlyUsd: number | null
  perRunUsd: number | null
  confirmAboveUsd: number | null
}
export interface BudgetOverview {
  budget: BudgetLimits
  spentThisMonthUsd: number
  tokensThisMonth: number
  prices: ModelPrice[]
  defaults: ModelPrice
  recent: { kind: string; refId: string; estimatedUsd: number; actualUsd: number; estimatedTokens: number; actualTokens: number; estimatedCalls: number; actualCalls: number; aborted: boolean; createdAt: string }[]
  tokenError: number | null
  measuredRuns: number
}
export interface RunLimits {
  capUsd?: number
  capCalls?: number
  confirm?: boolean
}
export type JudgedMetric = 'faithfulness' | 'correctness' | 'relevance'
export interface CalibrationItem {
  labelId: string
  metric: JudgedMetric
  itemKey: string
  question: string
  expectedAnswer?: string
  answer?: string
  claim?: string
  evidence?: string
  options: string[]
}
export interface CalibrationSample {
  metric: JudgedMetric
  size: number
  stillUnlabeled: number
  target: number
  items: CalibrationItem[]
}
export interface CalibrationMetric {
  metric: JudgedMetric
  judgeModel: string
  rubricVersion: string
  n: number
  agreement?: number
  kappa?: number
  status: JudgeStatus
  needsMoreLabels: boolean
  labels: string[]
  confusion: number[][]
  precision: Record<string, number>
  recall: Record<string, number>
  unlabeled: number
}
export interface CalibrationSummary {
  judgeModel: string
  rubricVersion: string
  minimumLabels: number
  trustedKappa: number
  metrics: CalibrationMetric[]
  warnings: string[]
}

const csrf = { 'X-Glassbox-CSRF': '1' }

async function toError(res: Response): Promise<ApiError> {
  let detail = res.statusText
  try {
    detail = (await res.json()).detail ?? detail // problem+json
  } catch {
    /* no body */
  }
  const ra = Number(res.headers.get('Retry-After'))
  return new ApiError(res.status, detail, Number.isFinite(ra) && ra > 0 ? ra : undefined)
}

function withHeaders(init: RequestInit, extra?: Record<string, string>): RequestInit {
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  if (workspaceId) headers.set('X-Workspace-Id', workspaceId)
  for (const [k, v] of Object.entries(extra ?? {})) headers.set(k, v)
  return { ...init, headers, credentials: 'same-origin' }
}

// Access tokens last 15 minutes. One shared refresh serves every request that hits a 401 at the same time
// (refresh tokens rotate, so two concurrent refreshes would look like token theft to the server).
let refreshing: Promise<boolean> | null = null
export function refreshSession(): Promise<boolean> {
  refreshing ??= (async () => {
    try {
      const res = await fetch('/api/auth/refresh', { method: 'POST', headers: csrf, credentials: 'same-origin' })
      if (!res.ok) return false
      accessToken = ((await res.json()) as AuthResponse).accessToken
      return true
    } catch {
      return false
    } finally {
      refreshing = null
    }
  })()
  return refreshing
}

/** fetch with auth headers; on 401 it refreshes the session once and retries. */
export async function authedFetch(path: string, init: RequestInit = {}, extra?: Record<string, string>): Promise<Response> {
  let res = await fetch(path, withHeaders(init, extra))
  if (res.status === 401 && !path.startsWith('/api/auth/')) {
    if (await refreshSession()) {
      res = await fetch(path, withHeaders(init, extra))
    } else {
      authLostHandler?.()
    }
  }
  return res
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await authedFetch(path, init)
  if (!res.ok) throw await toError(res)
  return res.status === 204 ? (undefined as T) : res.json()
}

export const api = {
  register: (email: string, password: string) =>
    request<AuthResponse>('/api/auth/register', { method: 'POST', body: JSON.stringify({ email, password }) }),
  login: (email: string, password: string) =>
    request<AuthResponse>('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }),
  refresh: () => request<AuthResponse>('/api/auth/refresh', { method: 'POST', headers: csrf }),
  logout: () => request<void>('/api/auth/logout', { method: 'POST', headers: csrf }),
  workspaces: () => request<Workspace[]>('/api/workspaces'),
  documents: (limit = 200) => request<DocumentPage>(`/api/documents?limit=${limit}`),
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<UploadResult>('/api/documents', { method: 'POST', body: form })
  },
  deleteDocument: (id: string) => request<void>(`/api/documents/${id}`, { method: 'DELETE' }),
  documentContent: (id: string) => request<DocumentContent>(`/api/documents/${id}/content`),
  trace: (id: string) => request<Trace>(`/api/traces/${id}`),
  testsets: () => request<TestSet[]>('/api/testsets'),
  testset: (id: string) => request<TestSet>(`/api/testsets/${id}`),
  generateTestset: (input: GenerateInput) =>
    request<{ testset: TestSet; estimatedLlmCalls: number }>('/api/testsets/generate', { method: 'POST', body: JSON.stringify(input) }),
  testsetQuestions: (id: string) => request<{ items: TestQuestion[]; cursor?: string }>(`/api/testsets/${id}/questions?limit=200`),
  patchQuestion: (id: string, patch: QuestionPatch) => request<TestQuestion>(`/api/questions/${id}`, { method: 'PATCH', body: JSON.stringify(patch) }),
  addQuestion: (testsetId: string, q: { question: string; expectedAnswer: string; qtype: QuestionType; spans: SpanInput[] }) =>
    request<TestQuestion>(`/api/testsets/${testsetId}/questions`, { method: 'POST', body: JSON.stringify(q) }),
  bulkAccept: (id: string) => request<{ accepted: number }>(`/api/testsets/${id}/bulk-accept`, { method: 'POST' }),
  finalizeTestset: (id: string, allowPending: boolean) =>
    request<TestSet>(`/api/testsets/${id}/finalize`, { method: 'POST', body: JSON.stringify({ allowPending }) }),
  newTestsetVersion: (id: string) => request<TestSet>(`/api/testsets/${id}/versions`, { method: 'POST' }),
  testsetDiff: (id: string, against: string) => request<TestSetDiff>(`/api/testsets/${id}/diff?against=${against}`),
  experiments: () => request<Experiment[]>('/api/experiments'),
  experiment: (id: string) => request<Experiment>(`/api/experiments/${id}`),
  estimateExperiment: (testsetId: string, mode: ExperimentMode, configs: ConfigInput[], questionLimit?: number) =>
    request<Estimate>('/api/experiments/estimate', { method: 'POST', body: JSON.stringify({ testsetId, mode, configs, questionLimit }) }),
  createExperiment: (testsetId: string, name: string, mode: ExperimentMode, configs: ConfigInput[], questionLimit?: number, limits: RunLimits = {}) =>
    request<Experiment>('/api/experiments', { method: 'POST', body: JSON.stringify({ testsetId, name, mode, configs, questionLimit, ...limits }) }),
  giveFeedback: (traceId: string, rating: 'UP' | 'DOWN', comment?: string) =>
    request<FeedbackItem>(`/api/traces/${traceId}/feedback`, { method: 'POST', body: JSON.stringify({ rating, comment }) }),
  feedbackList: (status: string) => request<FeedbackItem[]>(`/api/feedback?status=${status}`),
  feedbackSummary: () => request<FeedbackSummary>('/api/feedback/summary'),
  feedbackDetail: (id: string) => request<FeedbackDetail>(`/api/feedback/${id}`),
  diagnoseFeedback: (id: string, spans: SpanInput[]) => request<FeedbackDiagnosed>(`/api/feedback/${id}/diagnose`, { method: 'POST', body: JSON.stringify({ spans }) }),
  convertFeedback: (id: string, body: { testsetId: string; expectedAnswer?: string; qtype?: string; spans: SpanInput[] }) =>
    request<{ item: FeedbackItem; testsetId: string; newVersion: boolean }>(`/api/feedback/${id}/convert`, { method: 'POST', body: JSON.stringify(body) }),
  dismissFeedback: (id: string) => request<FeedbackItem>(`/api/feedback/${id}/dismiss`, { method: 'POST' }),
  documentVersions: (id: string) => request<DocumentVersion[]>(`/api/documents/${id}/versions`),
  documentImpact: (id: string, version: number) => request<DocumentImpact>(`/api/documents/${id}/versions/${version}/impact`),
  createReport: (kind: 'EXPERIMENT' | 'AUTOTUNE', refId: string, redact: boolean) =>
    request<ReportMeta>('/api/reports', { method: 'POST', body: JSON.stringify({ kind, refId, redact }) }),
  reportLinks: (id: string) => request<ReportLink[]>(`/api/reports/${id}/links`),
  createReportLink: (id: string, days: number) => request<CreatedReportLink>(`/api/reports/${id}/links`, { method: 'POST', body: JSON.stringify({ days }) }),
  revokeReportLink: (id: string, linkId: string) => request<void>(`/api/reports/${id}/links/${linkId}`, { method: 'DELETE' }),
  reportPdf: async (id: string): Promise<Blob> => {
    const res = await authedFetch(`/api/reports/${id}/pdf`, {})
    if (!res.ok) throw await toError(res)
    return res.blob()
  },
  budget: () => request<BudgetOverview>('/api/budget'),
  setBudget: (b: BudgetLimits) => request<BudgetLimits>('/api/budget', { method: 'PUT', body: JSON.stringify(b) }),
  setPrice: (p: ModelPrice) => request<ModelPrice[]>('/api/budget/prices', { method: 'PUT', body: JSON.stringify(p) }),
  deletePrice: (model: string) => request<ModelPrice[]>(`/api/budget/prices?model=${encodeURIComponent(model)}`, { method: 'DELETE' }),
  cancelExperiment: (id: string) => request<void>(`/api/experiments/${id}/cancel`, { method: 'POST' }),
  experimentResults: (id: string, run: string) => request<ExperimentResult[]>(`/api/experiments/${id}/results?run=${run}`),
  compareRuns: (a: string, b: string) => request<RunDiff>(`/api/experiments/compare?a=${a}&b=${b}`),
  diagnosis: (experimentId: string, run: string) => request<Diagnosis>(`/api/experiments/${experimentId}/diagnosis?run=${run}`),
  autotunes: () => request<AutotuneSummary[]>('/api/autotune'),
  autotune: (id: string) => request<Autotune>(`/api/autotune/${id}`),
  estimateAutotune: (input: AutotuneRequest) => request<AutotuneEstimate>('/api/autotune/estimate', { method: 'POST', body: JSON.stringify(input) }),
  createAutotune: (input: AutotuneRequest) => request<Autotune>('/api/autotune', { method: 'POST', body: JSON.stringify(input) }),
  cancelAutotune: (id: string) => request<void>(`/api/autotune/${id}/cancel`, { method: 'POST' }),
  promoteAutotune: (id: string) => request<PipelineConfig>(`/api/autotune/${id}/promote`, { method: 'POST' }),
  calibrationSummary: () => request<CalibrationSummary>('/api/calibration/summary'),
  calibrationSample: (metric: JudgedMetric, size = 40) =>
    request<CalibrationSample>('/api/calibration/sample', { method: 'POST', body: JSON.stringify({ metric, size }) }),
  calibrationLabels: (labels: { labelId: string; humanLabel: string }[]) =>
    request<CalibrationMetric[]>('/api/calibration/labels', { method: 'POST', body: JSON.stringify({ labels }) }),
  traces: (cursor?: string, kind?: 'CHAT' | 'EXPERIMENT') =>
    request<TracePage>(`/api/traces?limit=30${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}${kind ? `&kind=${kind}` : ''}`),
}

/** Reads a server-sent event stream from a fetch response and calls onEvent for each frame. Resolves when it ends. */
async function readEvents(res: Response, onEvent: (e: SseEvent) => void): Promise<void> {
  if (!res.body) throw new ApiError(res.status, 'The server sent no stream')
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  const parser = new SseParser()
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    for (const ev of parser.push(decoder.decode(value, { stream: true }))) onEvent(ev)
  }
}

/** Streams one chat turn. Refusals (401/404/429...) reject with an ApiError before any event is delivered. */
export async function streamChat(question: string, onEvent: (e: SseEvent) => void, signal: AbortSignal): Promise<void> {
  const res = await authedFetch('/api/chat', { method: 'POST', body: JSON.stringify({ question }), signal }, { Accept: 'text/event-stream' })
  if (!res.ok) throw await toError(res)
  await readEvents(res, onEvent)
}

/** Live document status for the current workspace. Resolves when the stream closes; the caller reconnects. */
export async function streamDocumentEvents(onEvent: (e: SseEvent) => void, signal: AbortSignal): Promise<void> {
  const res = await authedFetch('/api/events', { signal }, { Accept: 'text/event-stream' })
  if (!res.ok) throw await toError(res)
  await readEvents(res, onEvent)
}
