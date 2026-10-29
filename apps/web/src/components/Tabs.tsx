import { motion } from 'motion/react'
import { useId } from 'react'

/** Glass tabs with a prism pill that slides to the active one (a shared layout animation). Controlled: the caller holds the value. */
export function Tabs<T extends string>({ tabs, value, onChange, label }: {
  tabs: { id: T; label: string; count?: number }[]
  value: T
  onChange: (id: T) => void
  label: string
}) {
  const group = useId()
  return (
    <div role="tablist" aria-label={label} className="glass inline-flex rounded-2xl p-1">
      {tabs.map((t) => {
        const active = t.id === value
        return (
          <button
            key={t.id}
            role="tab"
            type="button"
            aria-selected={active}
            onClick={() => onChange(t.id)}
            className={`relative rounded-xl px-3.5 py-1.5 text-[0.82rem] font-medium transition-colors ${active ? 'text-[var(--on-prism)]' : 'text-muted hover:text-fg'}`}
          >
            {active && <motion.span layoutId={group} className="absolute inset-0 rounded-xl" style={{ background: 'var(--prism)' }} transition={{ type: 'spring', stiffness: 420, damping: 34 }} />}
            <span className="relative z-10 flex items-center gap-1.5">
              {t.label}
              {t.count !== undefined && <span className={`numeric text-[0.7rem] ${active ? 'opacity-70' : 'text-muted'}`}>{t.count}</span>}
            </span>
          </button>
        )
      })}
    </div>
  )
}
