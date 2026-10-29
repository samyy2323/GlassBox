import type { ReactNode } from 'react'
import { useCountUp } from '../lib/useCountUp'

/** A glass tile with one big number (it counts up when it appears), a small label above it and an optional hint below. */
export function Stat({ label, value, unit, decimals = 0, hint, tone = 'default', icon }: {
  label: string
  value: number | string
  unit?: string
  decimals?: number
  hint?: ReactNode
  tone?: 'default' | 'ok' | 'warn' | 'danger' | 'accent'
  icon?: ReactNode
}) {
  const tones = { default: 'text-fg', ok: 'text-ok', warn: 'text-warn', danger: 'text-danger', accent: 'text-gradient' }
  return (
    <div className="card glass-interactive">
      <div className="flex items-center justify-between gap-3">
        <p className="eyebrow !text-muted">{label}</p>
        {icon && <span className="text-muted" aria-hidden>{icon}</span>}
      </div>
      <p className={`mt-3 flex items-baseline gap-1.5 ${tones[tone]}`}>
        {typeof value === 'number' ? <Counted value={value} decimals={decimals} /> : <span className="display text-[2.6rem]">{value}</span>}
        {unit && <span className="numeric text-sm text-muted">{unit}</span>}
      </p>
      {hint && <p className="mt-2 text-[0.8rem] leading-snug text-muted">{hint}</p>}
    </div>
  )
}

function Counted({ value, decimals }: { value: number; decimals: number }) {
  const shown = useCountUp(value)
  return <span className="display text-[2.6rem] tabular-nums" aria-label={value.toFixed(decimals)}>{shown.toFixed(decimals)}</span>
}
