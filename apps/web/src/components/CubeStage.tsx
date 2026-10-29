import { lazy, Suspense, useState } from 'react'
import { loadCube, prefersReducedMotion, webglAvailable } from '../lib/webgl'
import { LogoMark } from './LogoMark'

// The 3D cube (three.js) is its own chunk: downloaded only where a CubeStage is shown, and started early by lib/webgl.preloadCube.
const GlassCube = lazy(loadCube)

/** The flat mark with a soft halo around it (CSS, so it has no edges): what is shown instead of the 3D cube without WebGL or with reduced motion. */
function StillMark({ size }: { size: number }) {
  return (
    <div className="grid h-full w-full place-items-center">
      <div className="relative grid place-items-center">
        <span aria-hidden className="absolute rounded-full" style={{ inset: '-55%', background: 'radial-gradient(closest-side, rgba(167,139,250,0.32), transparent 72%)' }} />
        <div className="relative animate-float"><LogoMark size={size} /></div>
      </div>
    </div>
  )
}

/**
 * The glass cube where it is the hero: the transparent 3D object when the browser can draw it, the flat mark when it cannot. The box has the size you give it; the
 * cube fits inside. While the 3D code loads nothing is drawn (`waiting="mark"` draws the flat mark instead), so a screen never shows one cube and then another.
 */
export function CubeStage({ className, stillSize = 180, onReady, spin, interactive, waiting = 'nothing' }: {
  className?: string
  stillSize?: number
  onReady?: () => void
  spin?: number
  interactive?: boolean
  waiting?: 'nothing' | 'mark'
}) {
  const [failed, setFailed] = useState(false)
  if (failed || prefersReducedMotion() || !webglAvailable()) {
    return (
      <div className={className}>
        <StillMark size={stillSize} />
      </div>
    )
  }
  return (
    <Suspense fallback={waiting === 'mark' ? <div className={className}><StillMark size={stillSize} /></div> : <div className={className} />}>
      <GlassCube className={className} onReady={onReady} onFail={() => setFailed(true)} spin={spin} interactive={interactive} />
    </Suspense>
  )
}
