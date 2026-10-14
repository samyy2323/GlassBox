import { Sparkles } from 'lucide-react'

/** A monochrome tile: the inverse of the page, so it is white on black in the dark theme and black on white in the light one. */
export function Logo({ size = 36, pulse = false }: { size?: number; pulse?: boolean }) {
  return (
    <span
      aria-hidden
      className={`grid shrink-0 place-items-center rounded-[30%] bg-fg text-canvas shadow-[0_6px_20px_rgb(var(--fg-rgb)/0.25)] ${pulse ? 'animate-glow-pulse' : ''}`}
      style={{ width: size, height: size }}
    >
      <Sparkles style={{ width: size * 0.5, height: size * 0.5 }} strokeWidth={2.2} />
    </span>
  )
}

export function Wordmark() {
  return (
    <span className="flex items-center gap-2.5">
      <Logo size={30} />
      <span className="text-[1.02rem] font-semibold tracking-tight">Glassbox</span>
    </span>
  )
}
