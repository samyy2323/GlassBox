import type { ReactNode } from 'react'

export type Tone = 'neutral' | 'accent' | 'ok' | 'warn' | 'danger'

const TONES: Record<Tone, string> = {
  neutral: 'border-[var(--line)] bg-[var(--glass-fill)] text-muted',
  accent: 'border-accent/35 bg-accent/10 text-accent',
  ok: 'border-ok/35 bg-ok/10 text-ok',
  warn: 'border-warn/40 bg-warn/10 text-warn',
  danger: 'border-danger/40 bg-danger/10 text-danger',
}

/** A small status pill. The colour is never the only signal: pass an icon or write the state in words. */
export function Badge({ tone = 'neutral', children, dot = false }: { tone?: Tone; children: ReactNode; dot?: boolean }) {
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-[0.7rem] font-semibold ${TONES[tone]}`}>
      {dot && <span className="h-1.5 w-1.5 rounded-full bg-current" aria-hidden />}
      {children}
    </span>
  )
}
