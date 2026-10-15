import type { AutotuneCandidate, AutotuneEffect, AutotuneObjective, AutotuneStatus } from './api'

// Pure helpers behind the AutoTune pages: names, progress, the layout of the quality-against-cost plot, and the table of what each
// setting did on average.

export const OBJECTIVE_LABEL: Record<AutotuneObjective, { title: string; text: string }> = {
  BEST_QUALITY: { title: 'Best quality', text: 'Find the configuration with the highest quality.' },
  CHEAPEST_ABOVE_FLOOR: { title: 'Cheapest above a quality floor', text: 'Among configurations at or above the floor, prefer the one that uses the fewest prompt tokens.' },
  FASTEST_WITHIN_CAP: { title: 'Best quality within a latency cap', text: 'Among configurations that answer within the cap, prefer the best quality.' },
}

export const STEPS = [
  { id: 'INDEXING', label: 'Indexing', text: 'Build the chunkings and embeddings that are missing.' },
  { id: 'STAGE1', label: 'Chunking and embeddings', text: 'Successive halving on the tuning questions.' },
  { id: 'STAGE2', label: 'Retrieval settings', text: 'Successive halving around the stage 1 winner.' },
  { id: 'STAGE3', label: 'Full answers', text: 'Finalists answer and are judged on the tuning questions (optional).' },
  { id: 'STAGE4', label: 'Held-out check', text: 'Finalists and the baseline on questions no choice has seen.' },
] as const

export const isActive = (status: AutotuneStatus) => status === 'QUEUED' || status === 'RUNNING'

/** Index of the current step in {@link STEPS}; -1 before the job starts, and the number of steps when it is done. */
export function stepIndex(stage: string, status: AutotuneStatus): number {
  if (status === 'DONE') return STEPS.length
  const i = STEPS.findIndex((s) => s.id === stage)
  return i
}

export type StepState = 'done' | 'current' | 'todo' | 'skipped'

export function stepStates(stage: string, status: AutotuneStatus, fullStage: boolean): StepState[] {
  const at = stepIndex(stage, status)
  return STEPS.map((s, i) => {
    if (s.id === 'STAGE3' && !fullStage) return 'skipped'
    if (i < at) return 'done'
    if (i === at && isActive(status)) return 'current'
    return 'todo'
  })
}

export interface PlotPoint {
  key: string
  label: string
  quality: number
  cost: number
  latencyMs: number
  frontier: boolean
  role: AutotuneCandidate['role']
}

export interface Placed extends PlotPoint {
  x: number
  y: number
}

/** A domain widened to round numbers and never degenerate, so a single point or equal values still draw. */
export function niceDomain(values: number[], step: number, floor?: number): [number, number] {
  if (values.length === 0) return [floor ?? 0, (floor ?? 0) + step]
  const clean = (v: number) => Math.round(v * 1e6) / 1e6 // 0.6000000000000001 is not an axis label
  let lo = clean(Math.floor(Math.min(...values) / step) * step)
  let hi = clean(Math.ceil(Math.max(...values) / step) * step)
  if (floor != null) lo = Math.max(floor, lo)
  if (hi - lo < step) hi = clean(lo + step)
  return [lo, hi]
}

/** The y axis of the quality plot: a rate, so never past 0 and 100%, padded so points do not sit on the border, and never degenerate when every value is equal. */
export function qualityDomain(values: number[]): [number, number] {
  if (values.length === 0) return [0, 1]
  const lo = Math.min(...values), hi = Math.max(...values)
  const pad = Math.max(0.05, (hi - lo) * 0.2)
  const clean = (v: number) => Math.round(v * 1e6) / 1e6
  let a = clean(Math.max(0, Math.floor((lo - pad) / 0.05) * 0.05))
  let b = clean(Math.min(1, Math.ceil((hi + pad) / 0.05) * 0.05))
  if (b - a < 0.05) a = clean(Math.max(0, b - 0.05))
  if (b - a < 0.05) b = clean(a + 0.05)
  return [a, b]
}

/** Position candidates on a plot of prompt tokens (left to right, less is better) against held-out quality (bottom to top). */
export function placePoints(points: PlotPoint[], width: number, height: number): { placed: Placed[]; xDomain: [number, number]; yDomain: [number, number] } {
  const xDomain = niceDomain(points.map((p) => p.cost), 100, 0)
  const yd = qualityDomain(points.map((p) => p.quality))
  const placed = points.map((p) => ({
    ...p,
    x: ((p.cost - xDomain[0]) / (xDomain[1] - xDomain[0])) * width,
    y: height - ((p.quality - yd[0]) / (yd[1] - yd[0])) * height,
  }))
  return { placed, xDomain, yDomain: yd }
}

/** The frontier as a staircase through the frontier points, left to right, for drawing the line that joins them. */
export function frontierPath(placed: Placed[]): string {
  const f = placed.filter((p) => p.frontier).sort((a, b) => a.x - b.x || a.y - b.y)
  return f.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ')
}

export interface EffectGroup {
  dimension: string
  stage: AutotuneEffect['stage']
  /** Best average minus worst average: how much this setting mattered. */
  spread: number
  rows: { value: string; averageQuality: number; n: number; best: boolean }[]
}

/** What each setting did on average, settings that mattered most first, best value first within a setting. */
export function effectGroups(effects: AutotuneEffect[]): EffectGroup[] {
  const by = new Map<string, AutotuneEffect[]>()
  for (const e of effects) by.set(`${e.stage}|${e.dimension}`, [...(by.get(`${e.stage}|${e.dimension}`) ?? []), e])
  const groups = [...by.values()].map((list) => {
    const sorted = [...list].sort((a, b) => b.averageQuality - a.averageQuality || a.value.localeCompare(b.value))
    const best = sorted[0].averageQuality
    return {
      dimension: sorted[0].dimension,
      stage: sorted[0].stage,
      spread: best - sorted[sorted.length - 1].averageQuality,
      rows: sorted.map((e) => ({ value: e.value, averageQuality: e.averageQuality, n: e.n, best: e.averageQuality === best })),
    }
  })
  return groups.sort((a, b) => b.spread - a.spread || a.dimension.localeCompare(b.dimension))
}

/** One sentence about the winner's curse: how much of the tuning score survived on questions the search never saw. */
export function shrinkageText(tuning: number | undefined, heldOut: number, shrinkage: number | undefined): string | null {
  if (tuning == null || shrinkage == null) return null
  const t = Math.round(tuning * 1000) / 10, h = Math.round(heldOut * 1000) / 10, d = Math.round(Math.abs(shrinkage) * 1000) / 10
  if (d < 0.05) return `The same score on the tuning questions (${t}%) and on the held-out ones (${h}%).`
  return shrinkage > 0
    ? `${t}% on the questions it was tuned on, ${h}% on the held-out ones: ${d.toFixed(1)} points lower. The best of many looks better than it is; the held-out figure is the honest one.`
    : `${t}% on the questions it was tuned on, ${h}% on the held-out ones: ${d.toFixed(1)} points higher, which is luck of the small held-out sample, not a sign of skill.`
}

export function describeConfig(c: AutotuneCandidate['config']): string[] {
  const out: string[] = []
  out.push(c.chunking ? `${c.chunking.strategy.replace('_', '-')} chunks of ${c.chunking.size} tokens${c.chunking.overlapPct ? `, ${c.chunking.overlapPct}% overlap` : ''}` : 'default chunks (recursive, 512 tokens)')
  if (c.retrieval.model) out.push(c.retrieval.model)
  out.push(c.retrieval.mode === 'HYBRID' ? `hybrid search ${c.retrieval.wVec}:${c.retrieval.wKw}` : `${c.retrieval.mode.toLowerCase()} search`)
  out.push(`${c.retrieval.kFused} candidates`)
  out.push(c.reranker === 'NONE' ? 'no reranker' : 'cross-encoder reranker')
  out.push(`top ${c.topN}`)
  out.push(`${c.tokenBudget}-token budget`)
  return out
}
