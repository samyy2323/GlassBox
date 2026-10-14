import { useEffect, useState } from 'react'
import { SPLASH_FADE_MS, SPLASH_MS, splashPhase, type SplashPhase } from '../lib/splash'

/** Full-screen intro shown once per page load for 3 seconds: the name, a line of purpose, and a bar that fills over exactly that time. */
export function Splash() {
  const [phase, setPhase] = useState<SplashPhase>('show')

  useEffect(() => {
    const started = performance.now()
    const tick = () => setPhase(splashPhase(performance.now() - started))
    const id = window.setInterval(tick, 100)
    const done = window.setTimeout(() => setPhase('done'), SPLASH_MS)
    return () => {
      window.clearInterval(id)
      window.clearTimeout(done)
    }
  }, [])

  if (phase === 'done') return null
  return (
    <div
      role="status"
      aria-label="Loading Glassbox"
      className="fixed inset-0 z-[100] grid place-items-center bg-canvas"
      style={{ opacity: phase === 'fade' ? 0 : 1, transition: `opacity ${SPLASH_FADE_MS}ms ease`, pointerEvents: phase === 'fade' ? 'none' : 'auto' }}
    >
      <div className="flex flex-col items-center px-6 text-center">
        <h1 className="animate-fade-up text-5xl font-semibold tracking-tight sm:text-6xl">Glassbox</h1>
        <p className="mt-4 animate-fade-up text-[0.95rem] text-muted" style={{ animationDelay: '250ms' }}>Ask your documents. Trust the answer.</p>
        <div className="mt-10 h-[3px] w-44 overflow-hidden rounded-full bg-fg/10" aria-hidden>
          <div className="h-full origin-left rounded-full bg-fg" style={{ animation: `splash-fill ${SPLASH_MS - SPLASH_FADE_MS}ms linear forwards` }} />
        </div>
      </div>
      <style>{'@keyframes splash-fill { from { transform: scaleX(0) } to { transform: scaleX(1) } }'}</style>
    </div>
  )
}
