import { LogoMark } from './LogoMark'

/** A small glass cube turning in place: the loading indicator of the whole app. `label` is what a screen reader hears. */
export function Spinner({ size = 28, label = 'Loading' }: { size?: number; label?: string }) {
  return (
    <span role="status" aria-label={label} className="inline-grid place-items-center" style={{ width: size, height: size, perspective: size * 4 }}>
      <span className="cube-spin inline-block">
        <LogoMark size={size} />
      </span>
      <style>{'.cube-spin{animation:cube-spin 2.4s cubic-bezier(.65,0,.35,1) infinite;transform-style:preserve-3d}@keyframes cube-spin{0%{transform:rotateY(0)}60%,100%{transform:rotateY(360deg)}}@media (prefers-reduced-motion:reduce){.cube-spin{animation:none}}'}</style>
    </span>
  )
}
