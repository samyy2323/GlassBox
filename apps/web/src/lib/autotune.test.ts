import { describe, expect, it } from 'vitest'
import type { AutotuneEffect, Diagnosis, PipelineConfig, Recommendation } from './api'
import { describeConfig, effectGroups, frontierPath, niceDomain, placePoints, qualityDomain, shrinkageText, stepStates, type PlotPoint } from './autotune'
import { configToInput, followUpConfigs, stageBars, stageTypeMatrix } from './diagnosis'

const point = (key: string, quality: number, cost: number, frontier = false): PlotPoint => ({ key, label: key, quality, cost, latencyMs: 10, frontier, role: 'FINALIST' })

describe('progress through the search', () => {
  it('marks earlier steps done, the current one active, and skips the full-answer step when it is off', () => {
    expect(stepStates('STAGE2', 'RUNNING', false)).toEqual(['done', 'done', 'current', 'skipped', 'todo'])
    expect(stepStates('STAGE3', 'RUNNING', true)).toEqual(['done', 'done', 'done', 'current', 'todo'])
    expect(stepStates('QUEUED', 'QUEUED', true)).toEqual(['todo', 'todo', 'todo', 'todo', 'todo'])
    expect(stepStates('DONE', 'DONE', false)).toEqual(['done', 'done', 'done', 'skipped', 'done'])
  })
})

describe('the quality against cost plot', () => {
  it('rounds the axes out so no point sits on the border and a single point still draws', () => {
    expect(niceDomain([0.62, 0.81], 0.05, 0)).toEqual([0.6, 0.85])
    expect(niceDomain([0.5], 0.05, 0)[1]).toBeGreaterThan(niceDomain([0.5], 0.05, 0)[0])
    expect(niceDomain([], 100, 0)).toEqual([0, 100])
    expect(niceDomain([420, 900], 100, 0)).toEqual([400, 900])
  })

  it('keeps the quality axis within 0 and 100% whatever the values', () => {
    expect(qualityDomain([1, 1, 1])).toEqual([0.9, 1])
    expect(qualityDomain([0.62, 0.81])).toEqual([0.55, 0.9])
    expect(qualityDomain([0, 0])).toEqual([0, 0.05])
    expect(qualityDomain([])).toEqual([0, 1])
    for (const v of [[0.97], [0.5, 1], [0.01, 0.02]]) {
      const [lo, hi] = qualityDomain(v)
      expect(lo).toBeGreaterThanOrEqual(0)
      expect(hi).toBeLessThanOrEqual(1)
      expect(hi - lo).toBeGreaterThanOrEqual(0.05 - 1e-9)
    }
  })

  it('puts cheap on the left and good at the top', () => {
    const { placed } = placePoints([point('cheap', 0.6, 200), point('rich', 0.9, 800)], 400, 200)
    const cheap = placed.find((p) => p.key === 'cheap')!, rich = placed.find((p) => p.key === 'rich')!
    expect(cheap.x).toBeLessThan(rich.x)
    expect(rich.y).toBeLessThan(cheap.y)
    for (const p of placed) {
      expect(p.x).toBeGreaterThanOrEqual(0)
      expect(p.x).toBeLessThanOrEqual(400)
      expect(p.y).toBeGreaterThanOrEqual(0)
      expect(p.y).toBeLessThanOrEqual(200)
    }
  })

  it('draws the frontier left to right through the frontier points only', () => {
    const { placed } = placePoints([point('b', 0.9, 800, true), point('x', 0.5, 900), point('a', 0.7, 200, true)], 400, 200)
    const path = frontierPath(placed)
    expect(path.startsWith('M')).toBe(true)
    expect(path.match(/[ML]/g)).toHaveLength(2)
    expect(frontierPath(placePoints([point('only', 0.5, 100)], 100, 100).placed)).toBe('')
  })
})

describe('what each setting did', () => {
  const e = (dimension: string, value: string, averageQuality: number, stage: AutotuneEffect['stage'] = 'STAGE1'): AutotuneEffect => ({ stage, dimension, value, averageQuality, n: 3 })

  it('puts the setting that mattered most first and the best value first inside it', () => {
    const groups = effectGroups([e('top n', '3', 0.7, 'STAGE2'), e('top n', '10', 0.72, 'STAGE2'), e('chunking', 'fixed', 0.5), e('chunking', 'structure', 0.9), e('chunking', 'recursive', 0.6)])
    expect(groups.map((g) => g.dimension)).toEqual(['chunking', 'top n'])
    expect(groups[0].rows.map((r) => r.value)).toEqual(['structure', 'recursive', 'fixed'])
    expect(groups[0].rows.map((r) => r.best)).toEqual([true, false, false])
    expect(groups[0].spread).toBeCloseTo(0.4)
  })

  it('keeps a setting that appears in both stages apart', () => {
    expect(effectGroups([e('x', 'a', 0.1), e('x', 'b', 0.2), e('x', 'a', 0.3, 'STAGE2'), e('x', 'b', 0.4, 'STAGE2')])).toHaveLength(2)
  })
})

describe('the winner’s curse sentence', () => {
  it('says how much of the tuning score survived', () => {
    expect(shrinkageText(0.9, 0.8, 0.1)).toContain('10.0 points lower')
    expect(shrinkageText(0.8, 0.85, -0.05)).toContain('points higher')
    expect(shrinkageText(0.8, 0.8, 0)).toContain('The same score')
    expect(shrinkageText(undefined, 0.8, undefined)).toBeNull()
  })
})

const config: PipelineConfig = { retrieval: { mode: 'VECTOR', model: 'bge-small-en-v1.5', rrfK: 60, wVec: 1, wKw: 1, kFused: 50 }, reranker: 'NONE', topN: 5, tokenBudget: 3000, chunking: { strategy: 'fixed', size: 512, overlapPct: 0 } }

describe('describing a configuration', () => {
  it('lists every setting in words', () => {
    expect(describeConfig(config)).toEqual(['fixed chunks of 512 tokens', 'bge-small-en-v1.5', 'vector search', '50 candidates', 'no reranker', 'top 5', '3000-token budget'])
    expect(describeConfig({ ...config, chunking: undefined, reranker: 'CROSS_ENCODER' })[0]).toBe('default chunks (recursive, 512 tokens)')
  })
})

describe('the failure report', () => {
  it('lays out the bars in pipeline order, longest bar full width', () => {
    const bars = stageBars({ RANKING_MISS: 4, CHUNKING_MISS: 1, GENERATION_MISS: 0, RETRIEVAL_MISS: 3 })
    expect(bars.map((b) => b.stage)).toEqual(['CHUNKING_MISS', 'RETRIEVAL_MISS', 'RANKING_MISS'])
    expect(bars.map((b) => b.width)).toEqual([0.25, 0.75, 1])
    expect(bars.reduce((s, b) => s + b.share, 0)).toBeCloseTo(1)
    expect(stageBars({})).toEqual([])
  })

  it('builds the stage by question type matrix from what is there', () => {
    const m = stageTypeMatrix({ RANKING_MISS: { FACTUAL: 2, NUMERIC: 1 }, CONTEXT_MISS: { NUMERIC: 4 }, GENERATION_MISS: {} }, ['FACTUAL', 'NUMERIC', 'MULTI_HOP', 'COMPARISON', 'UNANSWERABLE'])
    expect(m.types).toEqual(['FACTUAL', 'NUMERIC'])
    expect(m.rows.map((r) => [r.stage, r.total])).toEqual([['RANKING_MISS', 3], ['CONTEXT_MISS', 4]])
    expect(m.rows[1].cells).toEqual([{ qtype: 'FACTUAL', count: 0 }, { qtype: 'NUMERIC', count: 4 }])
  })

  it('turns a recommendation into the run as it was and the run with the one change', () => {
    const d: Pick<Diagnosis, 'config' | 'runLabel'> = { config, runLabel: 'Vector' }
    const rec: Recommendation = { stage: 'RANKING_MISS', count: 3, percent: 50, text: 't', change: { reranker: 'CROSS_ENCODER' }, changeLabel: 'Add the cross-encoder reranker' }
    const [before, after] = followUpConfigs(d, rec)!
    expect(before).toEqual(configToInput(config, 'Vector (before)'))
    expect(after).toEqual({ ...before, reranker: 'CROSS_ENCODER', name: 'Vector: Add the cross-encoder reranker' })
    expect(before.chunking).toEqual({ strategy: 'fixed', size: 512, overlapPct: 0 })
    expect(before.embeddingModel).toBe('bge-small-en-v1.5')
    expect(followUpConfigs(d, { ...rec, change: undefined })).toBeNull()
    expect(followUpConfigs(d, { ...rec, change: {} })).toBeNull()
  })
})
