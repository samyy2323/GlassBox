import { describe, expect, it } from 'vitest'
import type { CalibrationItem, ComparisonRow, JudgeInfo, MetricValue, RunSummary } from './api'
import { confusionCell, kappaWords, labelsPayload, nextUnlabeled, optionForKey, statusLine } from './calibration'
import {
  forestDomain, forestRows, formatDelta, formatInterval, formatMetric, findMetric, heatCell, HEADLINE, isActive, judgeBadge, LOW_SAMPLE, METRIC_ORDER, metricLabel, outcomeTone,
  PRESETS, runProgress, scaleX, slicesPresent, uniquePreset,
} from './experiments'

const m = (metric: string, slice: string, value: number, n = 20, method: 'question' | 'cluster' = 'question'): MetricValue => ({ metric, slice, method, value, ciLow: value - 0.1, ciHigh: value + 0.1, n })

describe('formatting', () => {
  it('shows rates as percentages and latencies in milliseconds', () => {
    expect(formatMetric('context_pass', 0.8333)).toBe('83.3%')
    expect(formatMetric('mrr', 1)).toBe('100%')
    expect(formatMetric('latency_p50_ms', 12.4)).toBe('12 ms')
    expect(formatMetric('mrr', undefined)).toBe('–')
  })

  it('shows a value with its interval, or just the value when there is none', () => {
    expect(formatInterval('recall@5', m('recall@5', 'all', 0.8))).toBe('80% [70%, 90%]')
    expect(formatInterval('latency_p50_ms', { metric: 'latency_p50_ms', slice: 'all', method: 'question', value: 14, n: 5 })).toBe('14 ms')
    expect(formatInterval('mrr', undefined)).toBe('–')
  })

  it('signs a change in points', () => {
    expect(formatDelta(0.041)).toBe('+4.1 pts')
    expect(formatDelta(-0.075)).toBe('-7.5 pts')
    expect(formatDelta(0)).toBe('0.0 pts')
    expect(formatDelta(-0.0001)).toBe('0.0 pts')
  })

  it('knows every metric the server can send, in display order, with a label', () => {
    for (const metric of METRIC_ORDER) expect(metricLabel(metric)).not.toBe('')
    expect(metricLabel('something_new')).toBe('something_new')
    expect(HEADLINE.RETRIEVAL).toBe('context_pass')
    expect(HEADLINE.FULL).toBe('correctness')
  })

  it('finds a metric by slice and method', () => {
    const metrics = [m('mrr', 'all', 0.5), m('mrr', 'FACTUAL', 0.7), m('mrr', 'all', 0.6, 20, 'cluster')]
    expect(findMetric(metrics, 'mrr')?.value).toBe(0.5)
    expect(findMetric(metrics, 'mrr', 'FACTUAL')?.value).toBe(0.7)
    expect(findMetric(metrics, 'mrr', 'all', 'cluster')?.value).toBe(0.6)
    expect(findMetric(metrics, 'ndcg@10')).toBeUndefined()
  })
})

describe('judge badges', () => {
  const judge = (status: string): JudgeInfo => ({
    model: 'x', rubricVersion: 'v1', generationModel: 'x', nliModel: 'n', sameModelAsGenerator: true, warnings: [],
    metrics: { faithfulness: { status: 'calibrated' }, correctness: { status: status as 'unknown' }, relevance: { status: 'uncalibrated', kappa: 0.3, n: 40 } },
  })

  it('warns on judged metrics until their judge is calibrated', () => {
    expect(judgeBadge('correctness', judge('unknown'))).toBe('unknown')
    expect(judgeBadge('relevance', judge('unknown'))).toBe('uncalibrated')
    expect(judgeBadge('correctness', judge('calibrated'))).toBeNull()
    expect(judgeBadge('citation_precision', judge('unknown'))).toBeNull() // follows faithfulness, which is calibrated here
  })

  it('never warns on numbers that need no judge', () => {
    expect(judgeBadge('context_pass', judge('unknown'))).toBeNull()
    expect(judgeBadge('latency_p50_ms', judge('unknown'))).toBeNull()
    expect(judgeBadge('correctness', undefined)).toBeNull()
  })
})

describe('forest plot', () => {
  const row = (delta: number, low: number, high: number, outcome: ComparisonRow['outcome']): ComparisonRow => ({
    metric: 'context_pass', a: 0.5, b: 0.5 + delta, delta, ciLow: low, ciHigh: high, p: 0.01, pAdjusted: 0.01, test: 'mcnemar', outcome, verdict: '', n: 30,
  })

  it('colours a result by whether it helped, and flips for metrics where lower is better', () => {
    expect(outcomeTone('B_BETTER')).toBe('good')
    expect(outcomeTone('A_BETTER')).toBe('bad')
    expect(outcomeTone('NO_DIFFERENCE')).toBe('none')
    expect(outcomeTone('B_BETTER', false)).toBe('bad')
    expect(outcomeTone('A_BETTER', false)).toBe('good')
  })

  it('keeps the runs that have a comparison and names their tone', () => {
    const rows = forestRows([{ label: 'hybrid', row: row(0.2, 0.08, 0.32, 'B_BETTER') }, { label: 'missing', row: undefined }], 'context_pass')
    expect(rows).toEqual([{ label: 'hybrid', delta: 0.2, low: 0.08, high: 0.32, tone: 'good' }])
    expect(forestRows([{ label: 'x', row: row(0.2, 0.1, 0.3, 'B_BETTER') }], 'false_abstention')[0].tone).toBe('bad')
  })

  it('sizes the axis to hold every interval and zero, in whole steps', () => {
    expect(forestDomain([{ low: 0.08, high: 0.32 }])).toEqual([0, 0.35])
    expect(forestDomain([{ low: -0.12, high: 0.04 }])).toEqual([-0.15, 0.05])
    expect(forestDomain([{ low: -0.2, high: 0.3 }, { low: -0.05, high: 0.1 }])).toEqual([-0.2, 0.3])
    expect(forestDomain([])).toEqual([-0.05, 0.05])
  })

  it('maps values onto the plot width, with zero where the axis puts it', () => {
    expect(scaleX(0, [-0.2, 0.3], 500)).toBe(200)
    expect(scaleX(-0.2, [-0.2, 0.3], 500)).toBe(0)
    expect(scaleX(0.3, [-0.2, 0.3], 500)).toBe(500)
  })
})

describe('heatmap', () => {
  it('shades by value and marks slices with fewer than ten questions', () => {
    expect(heatCell(1, 20).opacity).toBeCloseTo(0.8)
    expect(heatCell(0, 20).opacity).toBeCloseTo(0.12)
    expect(heatCell(0.5, 20).lowSample).toBe(false)
    expect(heatCell(0.5, LOW_SAMPLE - 1).lowSample).toBe(true)
    expect(heatCell(undefined, 0)).toEqual({ opacity: 0, lowSample: true })
    expect(heatCell(7, 20).opacity).toBeLessThanOrEqual(0.8)
  })

  it('lists the question types that have results, in a fixed order', () => {
    const run = { metrics: [m('context_pass', 'NUMERIC', 1), m('context_pass', 'FACTUAL', 1), m('context_pass', 'all', 1), m('mrr', 'MULTI_HOP', 1)] } as unknown as RunSummary
    expect(slicesPresent([run], 'context_pass')).toEqual(['FACTUAL', 'NUMERIC'])
    expect(slicesPresent([], 'context_pass')).toEqual([])
  })
})

describe('runs and presets', () => {
  it('reports progress, and treats finished runs as complete', () => {
    expect(runProgress({ samplesDone: 5, status: 'RUNNING' }, 20)).toBe(25)
    expect(runProgress({ samplesDone: 5, status: 'DONE' }, 20)).toBe(100)
    expect(runProgress({ samplesDone: 0, status: 'PENDING' }, 0)).toBe(0)
    expect(runProgress({ samplesDone: 50, status: 'RUNNING' }, 20)).toBe(100)
    expect(isActive('RUNNING')).toBe(true)
    expect(isActive('QUEUED')).toBe(true)
    expect(isActive('DONE')).toBe(false)
  })

  it('offers the four starting points of the plan and gives each copy a free name', () => {
    expect(PRESETS.map((p) => p.id)).toEqual(['vector', 'keyword', 'hybrid', 'hybrid-rerank'])
    expect(uniquePreset('hybrid', []).name).toBe('Hybrid')
    expect(uniquePreset('hybrid', ['Hybrid']).name).toBe('Hybrid 2')
    expect(uniquePreset('hybrid', ['Hybrid', 'Hybrid 2']).name).toBe('Hybrid 3')
    expect(uniquePreset('hybrid-rerank', []).reranker).toBe('CROSS_ENCODER')
    expect(PRESETS[0].config.name).toBe('Vector')
  })
})

describe('calibration helpers', () => {
  const item = (id: string): CalibrationItem => ({ labelId: id, metric: 'correctness', itemKey: 'answer', question: 'q', options: ['correct', 'partial', 'wrong'] })
  const items = [item('a'), item('b'), item('c'), item('d')]

  it('moves to the next item without a label, wrapping round, and stops when none is left', () => {
    expect(nextUnlabeled(items, {}, 0)).toBe(1)
    expect(nextUnlabeled(items, { b: 'correct' }, 0)).toBe(2)
    expect(nextUnlabeled(items, { c: 'wrong', d: 'wrong' }, 1)).toBe(0)
    expect(nextUnlabeled(items, { a: 'x', b: 'x', c: 'x', d: 'x' }, 2)).toBe(-1)
    expect(nextUnlabeled([], {}, 0)).toBe(-1)
  })

  it('sends only what has been labelled, in the order of the items', () => {
    expect(labelsPayload(items, { c: 'wrong', a: 'correct' })).toEqual([{ labelId: 'a', humanLabel: 'correct' }, { labelId: 'c', humanLabel: 'wrong' }])
    expect(labelsPayload(items, {})).toEqual([])
  })

  it('picks an option with the number keys', () => {
    expect(optionForKey('1', ['correct', 'partial', 'wrong'])).toBe('correct')
    expect(optionForKey('3', ['correct', 'partial', 'wrong'])).toBe('wrong')
    expect(optionForKey('4', ['correct', 'partial', 'wrong'])).toBeNull()
    expect(optionForKey('0', ['a'])).toBeNull()
    expect(optionForKey('x', ['a'])).toBeNull()
  })

  it('reads kappa in plain words and says what is still missing', () => {
    expect(kappaWords(0.85)).toBe('almost perfect agreement')
    expect(kappaWords(0.6)).toBe('substantial agreement')
    expect(kappaWords(0.1)).toBe('little better than chance')
    expect(kappaWords(undefined)).toBe('not measured yet')
    expect(statusLine({ status: 'unknown', n: 0 }, 30, 0.6)).toContain('Not calibrated yet')
    expect(statusLine({ status: 'uncalibrated', n: 12, kappa: 0.9 }, 30, 0.6)).toContain('12 of 30 labels')
    expect(statusLine({ status: 'uncalibrated', n: 40, kappa: 0.3 }, 30, 0.6)).toContain('Uncalibrated')
    expect(statusLine({ status: 'calibrated', n: 40, kappa: 0.8 }, 30, 0.6)).toContain('Calibrated')
  })

  it('shades the agreement diagonal and the disagreements differently', () => {
    expect(confusionCell(0, 0, 8, 10)).toEqual({ tone: 'agree', opacity: 0.15 + 0.65 * 0.8 })
    expect(confusionCell(0, 1, 2, 10).tone).toBe('disagree')
    expect(confusionCell(1, 1, 0, 10).opacity).toBe(0)
    expect(confusionCell(1, 1, 0, 0).opacity).toBe(0)
  })
})
