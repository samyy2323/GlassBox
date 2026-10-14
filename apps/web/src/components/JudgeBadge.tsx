import { Link } from 'react-router-dom'
import type { JudgeInfo } from '../lib/api'
import { judgeBadge } from '../lib/experiments'

/** The warning that goes next to every number a judge produced, until the judge has been measured against human labels (SPEC 8). */
export function JudgeBadge({ metric, judge }: { metric: string; judge: JudgeInfo | undefined }) {
  const state = judgeBadge(metric, judge)
  if (!state) return null
  const key = metric === 'citation_precision' ? 'faithfulness' : metric
  const kappa = judge?.metrics[key]?.kappa
  return (
    <Link
      to="/calibration"
      className="ml-1.5 rounded-full border border-warn/50 px-1.5 py-px align-middle text-[0.62rem] font-semibold uppercase tracking-wide text-warn hover:bg-warn/10"
      title={state === 'unknown'
        ? 'This judge has never been checked against human labels. Calibrate it before relying on this number.'
        : `This judge agreed with human labels only to kappa ${kappa?.toFixed(2)}, below the 0.6 needed to trust it.`}
    >
      uncalibrated
    </Link>
  )
}
