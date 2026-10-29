import type { ReactNode } from 'react'
import { LogoMark } from './LogoMark'

/** What a list or page shows when there is nothing yet: a floating cube, one sentence on why it matters, and the next step as a button. */
export function EmptyState({ title, children, action }: { title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="card mx-auto flex max-w-xl flex-col items-center px-8 py-12 text-center animate-rise">
      <div className="relative grid place-items-center">
        <span aria-hidden className="absolute rounded-full" style={{ inset: '-60%', background: 'radial-gradient(closest-side, rgba(167,139,250,0.28), transparent 72%)' }} />
        <div className="relative animate-float"><LogoMark size={64} /></div>
      </div>
      <h2 className="display mt-6 text-[1.9rem]">{title}</h2>
      {children && <p className="mt-2 max-w-md text-[0.92rem] leading-relaxed text-muted">{children}</p>}
      {action && <div className="mt-6 flex flex-wrap justify-center gap-2">{action}</div>}
    </div>
  )
}
