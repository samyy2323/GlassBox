import type { ConfigInput, Diagnosis, FailureStage, PipelineConfig, Recommendation } from './api'

// Pure helpers behind the failure report: how each stage is named, how the bar chart and the stage-by-type matrix are laid out,
// and how a recommendation becomes the two configurations of a follow-up experiment.

/** In pipeline order: the first stage that fails is the one a question is labelled with. */
export const STAGE_ORDER: FailureStage[] = ['CHUNKING_MISS', 'RETRIEVAL_MISS', 'RANKING_MISS', 'CONTEXT_MISS', 'PARTIAL_RETRIEVAL', 'GENERATION_MISS', 'HALLUCINATED_ANSWER', 'BAD_TEST_QUESTION']

export const STAGE_INFO: Record<FailureStage, { label: string; hint: string }> = {
  CHUNKING_MISS: { label: 'Chunking', hint: 'No single chunk holds the evidence, so no search could have found it whole.' },
  RETRIEVAL_MISS: { label: 'Retrieval', hint: 'A chunk holds the evidence, but the search did not return it.' },
  RANKING_MISS: { label: 'Ranking', hint: 'The search returned it, but below the chunks that went into the prompt.' },
  CONTEXT_MISS: { label: 'Context', hint: 'It was in the top chunks but cut by the token budget or removed as a duplicate.' },
  PARTIAL_RETRIEVAL: { label: 'Partial', hint: 'A question that needs several passages got only some of them.' },
  GENERATION_MISS: { label: 'Generation', hint: 'All the evidence was in the prompt, but the answer is wrong.' },
  HALLUCINATED_ANSWER: { label: 'Hallucination', hint: 'An unanswerable question received an answer instead of "not in the documents".' },
  BAD_TEST_QUESTION: { label: 'Test question', hint: 'The evidence was there and the answer is wrong, but the question is ambiguous or not answerable from its evidence. Fix the test set.' },
}

export const SUB_LABEL: Record<string, string> = {
  UNFAITHFUL: 'says things the prompt does not',
  INCOMPLETE: 'incomplete',
  WRONG_REASONING: 'wrong reasoning',
  FALSE_ABSTENTION: 'refused though the answer was there',
}

export interface StageBar {
  stage: FailureStage
  count: number
  /** Share of all failures, 0 to 1. */
  share: number
  /** Width relative to the longest bar, 0 to 1. */
  width: number
}

/** Stages that have failures, in pipeline order, with the layout numbers of a horizontal bar chart. */
export function stageBars(byStage: Record<string, number>): StageBar[] {
  const present = STAGE_ORDER.filter((s) => (byStage[s] ?? 0) > 0)
  const total = present.reduce((sum, s) => sum + byStage[s], 0)
  const max = Math.max(0, ...present.map((s) => byStage[s]))
  return present.map((stage) => ({ stage, count: byStage[stage], share: total === 0 ? 0 : byStage[stage] / total, width: max === 0 ? 0 : byStage[stage] / max }))
}

export interface MatrixRow {
  stage: FailureStage
  cells: { qtype: string; count: number }[]
  total: number
}

/** Stage by question type: only stages with failures and only types that appear, with a stable order. */
export function stageTypeMatrix(byStageAndType: Record<string, Record<string, number>>, typeOrder: readonly string[]): { types: string[]; rows: MatrixRow[] } {
  const seen = new Set<string>()
  for (const s of STAGE_ORDER) for (const [t, n] of Object.entries(byStageAndType[s] ?? {})) if (n > 0) seen.add(t)
  const types = typeOrder.filter((t) => seen.has(t))
  const rows = STAGE_ORDER.flatMap((stage) => {
    const row = byStageAndType[stage] ?? {}
    const total = types.reduce((sum, t) => sum + (row[t] ?? 0), 0)
    return total === 0 ? [] : [{ stage, cells: types.map((qtype) => ({ qtype, count: row[qtype] ?? 0 })), total }]
  })
  return { types, rows }
}

/** What a run was configured with, in the shape of a new experiment's configuration. */
export function configToInput(config: PipelineConfig, name: string): ConfigInput {
  return {
    name,
    mode: config.retrieval.mode as ConfigInput['mode'],
    kFused: config.retrieval.kFused,
    rrfK: config.retrieval.rrfK,
    wVec: config.retrieval.wVec,
    wKw: config.retrieval.wKw,
    reranker: config.reranker as ConfigInput['reranker'],
    topN: config.topN,
    tokenBudget: config.tokenBudget,
    chunking: config.chunking,
    embeddingModel: config.retrieval.model,
    transformer: config.transformer,
  }
}

/**
 * The follow-up experiment a recommendation stands for: the run as it was, and the same run with exactly the one suggested change.
 * Null when the advice has no one-click change (a stronger model, a fixed test set).
 */
export function followUpConfigs(d: Pick<Diagnosis, 'config' | 'runLabel'>, rec: Recommendation): ConfigInput[] | null {
  if (!rec.change || Object.keys(rec.change).length === 0) return null
  const before = configToInput(d.config, `${d.runLabel} (before)`)
  const label = rec.changeLabel ?? 'with the change'
  return [before, { ...before, ...rec.change, name: `${d.runLabel}: ${label}` }]
}
