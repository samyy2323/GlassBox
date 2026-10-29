import { useCallback, useEffect, useRef, useState } from 'react'
import { SPLASH_FADE_MS, SPLASH_MS, splashPhase, type SplashPhase } from '../lib/splash'
import { prefersReducedMotion, webglAvailable } from '../lib/webgl'
import { Aurora } from './Aurora'
import { CubeStage } from './CubeStage'

const NAME = 'Glassbox'
/** The cube's code is already loading by now; if it has still not drawn a frame after this long, the screen starts anyway. */
const READY_GRACE_MS = 1500

/**
 * The opening seconds of every page load: a transparent glass cube turns on its corner, the name rises letter by letter, a fine prism line fills, and then the cube
 * swells and the whole screen dissolves into the app. The 3 seconds are counted from the moment the cube is on screen (or, with no WebGL, from the start), so the
 * cube is always seen turning for the full time. A click or any key skips it. The timing lives in lib/splash.ts and is tested.
 */
export function Splash() {
  const [phase, setPhase] = useState<SplashPhase>('show')
  const [begun, setBegun] = useState(false)
  const [ready, setReady] = useState(false)
  const started = useRef(0)

  const begin = useCallback(() => {
    if (started.current !== 0) return
    started.current = performance.now()
    setBegun(true)
  }, [])

  useEffect(() => {
    if (!webglAvailable() || prefersReducedMotion()) {
      begin() // no 3D cube to wait for
      return
    }
    const grace = window.setTimeout(begin, READY_GRACE_MS)
    return () => window.clearTimeout(grace)
  }, [begin])

  useEffect(() => {
    if (!begun) return
    const tick = () => setPhase(splashPhase(performance.now() - started.current))
    const id = window.setInterval(tick, 80)
    return () => window.clearInterval(id)
  }, [begun])

  // skipping jumps to the start of the fade, so the exit is always the same dissolve
  const skip = useCallback(() => {
    begin()
    const fadeStart = SPLASH_MS - SPLASH_FADE_MS
    if (performance.now() - started.current < fadeStart) started.current = performance.now() - fadeStart
  }, [begin])

  useEffect(() => {
    window.addEventListener('keydown', skip)
    return () => window.removeEventListener('keydown', skip)
  }, [skip])

  const onReady = useCallback(() => {
    setReady(true)
    begin()
  }, [begin])

  if (phase === 'done') return null
  const leaving = phase === 'fade'
  return (
    <div
      role="status"
      aria-label="Loading Glassbox"
      onClick={skip}
      className="fixed inset-0 z-[100] cursor-default overflow-hidden bg-canvas"
      style={{ opacity: leaving ? 0 : 1, transition: `opacity ${SPLASH_FADE_MS}ms ease`, pointerEvents: leaving ? 'none' : 'auto' }}
    >
      <Aurora />
      <div className="absolute inset-0 grid place-items-center px-6">
        <div className="flex flex-col items-center text-center">
          <div
            className="h-[min(62vmin,500px)] w-[min(62vmin,500px)]"
            style={{
              opacity: ready || !webglAvailable() ? 1 : 0,
              transform: leaving ? 'scale(1.3)' : ready || !webglAvailable() ? 'scale(1)' : 'scale(0.94)',
              transition: leaving ? `transform ${SPLASH_FADE_MS + 200}ms cubic-bezier(0.5, 0, 0.75, 0)` : 'opacity 1100ms ease, transform 1400ms cubic-bezier(0.22, 1, 0.36, 1)',
            }}
          >
            <CubeStage className="h-full w-full" stillSize={140} spin={0.085} onReady={onReady} />
          </div>

          <div role="heading" aria-level={1} aria-label={NAME} className="display -mt-[min(9vmin,70px)] flex text-[clamp(3.2rem,9.5vw,6.4rem)] font-light leading-none tracking-[0.02em]">
            {[...NAME].map((letter, i) => (
              <span key={i} aria-hidden className="animate-rise inline-block" style={{ animationDelay: `${500 + i * 70}ms` }}>{letter}</span>
            ))}
          </div>
          <p className="mt-6 animate-fade-in text-[0.7rem] font-medium uppercase tracking-[0.36em] text-muted" style={{ animationDelay: '1300ms', animationFillMode: 'both' }}>
            Ask your documents · Trust the answer
          </p>

          <div className="mt-10 h-px w-44 overflow-hidden bg-fg/10" aria-hidden>
            {begun && <div key="bar" className="h-full origin-left" style={{ background: 'var(--prism)', animation: `splash-fill ${SPLASH_MS - SPLASH_FADE_MS}ms linear forwards` }} />}
          </div>
        </div>
      </div>
      <style>{'@keyframes splash-fill { from { transform: scaleX(0) } to { transform: scaleX(1) } }'}</style>
    </div>
  )
}
