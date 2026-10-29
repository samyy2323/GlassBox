import { useEffect, useRef, useState } from 'react'

/** Ease-out exponential: fast at first, settling gently. */
export function easeOutExpo(t: number): number {
  return t >= 1 ? 1 : 1 - Math.pow(2, -10 * Math.max(0, t))
}

/** The number to show `elapsedMs` into an animation from `from` to `to` lasting `durationMs`. Pure, so the arithmetic can be tested. */
export function countUpValue(from: number, to: number, elapsedMs: number, durationMs: number): number {
  if (durationMs <= 0 || elapsedMs >= durationMs) return to
  return from + (to - from) * easeOutExpo(elapsedMs / durationMs)
}

function reducedMotion(): boolean {
  return typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** Animates a number toward `target` whenever it changes. With reduced motion it just shows the target. */
export function useCountUp(target: number, durationMs = 900): number {
  const [value, setValue] = useState(() => (reducedMotion() ? target : 0))
  const shown = useRef(value)
  shown.current = value

  useEffect(() => {
    if (!Number.isFinite(target) || reducedMotion()) {
      setValue(target)
      return
    }
    const from = shown.current
    const started = performance.now()
    let frame = 0
    const tick = (now: number) => {
      setValue(countUpValue(from, target, now - started, durationMs))
      if (now - started < durationMs) frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [target, durationMs])

  return value
}
