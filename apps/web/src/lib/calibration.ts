import type { CalibrationItem, CalibrationMetric, JudgedMetric } from './api'

// Pure helpers for the calibration page: wording, the labelling queue, and how agreement is presented.

export const JUDGED: JudgedMetric[] = ['faithfulness', 'correctness', 'relevance']

export const METRIC_TEXT: Record<JudgedMetric, { title: string; task: string }> = {
  faithfulness: { title: 'Faithfulness', task: 'Does the passage say what the statement claims?' },
  correctness: { title: 'Correctness', task: 'Does the answer state the same facts as the expected answer?' },
  relevance: { title: 'Relevance', task: 'Does the answer address the question that was asked (true or not)?' },
}

export const OPTION_TEXT: Record<string, string> = {
  supported: 'Supported',
  partial: 'Partly',
  unsupported: 'Not supported',
  correct: 'Correct',
  wrong: 'Wrong',
  yes: 'Yes',
  partly: 'Partly',
  no: 'No',
}

export const optionText = (option: string) => OPTION_TEXT[option] ?? option

/** Index of the next item without a label, looking forward from {@code from} and then wrapping round; -1 when everything is labelled. */
export function nextUnlabeled(items: CalibrationItem[], labels: Record<string, string>, from: number): number {
  for (let step = 1; step <= items.length; step++) {
    const i = (from + step) % items.length
    if (labels[items[i].labelId] == null) return i
  }
  return -1
}

/** The request body for the labels given so far; items the person skipped are simply left out. */
export function labelsPayload(items: CalibrationItem[], labels: Record<string, string>): { labelId: string; humanLabel: string }[] {
  return items.filter((i) => labels[i.labelId] != null).map((i) => ({ labelId: i.labelId, humanLabel: labels[i.labelId] }))
}

/** Which keyboard digit picks an option: 1 for the first, 2 for the second, and so on. Null when the key is not a valid choice. */
export function optionForKey(key: string, options: string[]): string | null {
  const n = Number(key)
  return Number.isInteger(n) && n >= 1 && n <= options.length ? options[n - 1] : null
}

/** Plain-language reading of kappa, using the usual bands. */
export function kappaWords(kappa: number | undefined): string {
  if (kappa == null) return 'not measured yet'
  if (kappa >= 0.8) return 'almost perfect agreement'
  if (kappa >= 0.6) return 'substantial agreement'
  if (kappa >= 0.4) return 'moderate agreement'
  if (kappa >= 0.2) return 'weak agreement'
  return 'little better than chance'
}

/** One line saying where a judge stands, and what is missing. */
export function statusLine(m: Pick<CalibrationMetric, 'status' | 'n' | 'kappa'>, minimum: number, trusted: number): string {
  if (m.status === 'unknown') return 'Not calibrated yet: label a sample to measure it.'
  if (m.n < minimum) return `${m.n} of ${minimum} labels so far. Too few to trust the number yet.`
  return m.status === 'calibrated'
    ? `Calibrated: kappa ${m.kappa?.toFixed(2)} is at or above ${trusted}.`
    : `Uncalibrated: kappa ${m.kappa?.toFixed(2)} is below ${trusted}, so judged scores carry a warning.`
}

/** Shade of a confusion matrix cell: the diagonal (agreement) in green, off-diagonal (disagreement) in red, stronger for a bigger share of its row. */
export function confusionCell(row: number, col: number, count: number, rowTotal: number): { tone: 'agree' | 'disagree'; opacity: number } {
  return { tone: row === col ? 'agree' : 'disagree', opacity: rowTotal === 0 || count === 0 ? 0 : 0.15 + 0.65 * (count / rowTotal) }
}
