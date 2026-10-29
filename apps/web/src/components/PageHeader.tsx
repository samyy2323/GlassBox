import type { ReactNode } from 'react'

/**
 * The top of a page: a small mono eyebrow, a large serif title, one line of plain explanation, and the page's actions on the right.
 * Every page uses this, so the app has one voice. `compact` is for detail pages whose title is a user's own name for something and may be long.
 */
export function PageHeader({ eyebrow, title, description, actions, icon, compact = false }: {
  eyebrow?: string
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  icon?: ReactNode
  compact?: boolean
}) {
  return (
    <header className="flex flex-wrap items-end justify-between gap-x-6 gap-y-4 animate-rise">
      <div className="min-w-0 max-w-3xl">
        {eyebrow && (
          <p className="eyebrow mb-3 flex items-center gap-2">
            <span className="h-px w-6 bg-accent/60" aria-hidden />
            {eyebrow}
          </p>
        )}
        <h1 className={`flex min-w-0 items-center gap-3 ${compact ? 'truncate text-[2rem] leading-[1.1]' : 'text-[2.6rem] leading-[1.02] sm:text-[3.2rem]'}`}>
          {icon}
          <span className={compact ? 'truncate' : undefined}>{title}</span>
        </h1>
        {description && <p className="mt-3 max-w-2xl text-[0.95rem] leading-relaxed text-muted">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </header>
  )
}
