import { useId } from 'react'

/**
 * The Glassbox mark: a glass cube balanced on one corner, seen along its body diagonal, so its outline is a hexagon and three faces meet at the near corner.
 * A second, smaller cube inside gives the glass depth. One drawing at every size (sidebar, browser tab, README). A halo, where one is wanted, is CSS around it:
 * an SVG blur filter is clipped to a rectangle and draws a visible box.
 * The WebGL cube on the splash screen is the same object made real.
 */
export function LogoMark({ size = 32, className, title }: { size?: number; className?: string; title?: string }) {
  const uid = useId().replace(/[^a-zA-Z0-9]/g, '')
  const id = (name: string) => `${name}-${uid}`
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" role={title ? 'img' : undefined} aria-label={title} aria-hidden={title ? undefined : true} className={className} fill="none">
      {title && <title>{title}</title>}
      <defs>
        <linearGradient id={id('top')} x1="32" y1="4" x2="32" y2="32" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#ffffff" stopOpacity="0.95" />
          <stop offset="1" stopColor="#67e8f9" stopOpacity="0.6" />
        </linearGradient>
        <linearGradient id={id('left')} x1="8" y1="18" x2="32" y2="60" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#a78bfa" stopOpacity="0.85" />
          <stop offset="1" stopColor="#4f46e5" stopOpacity="0.4" />
        </linearGradient>
        <linearGradient id={id('right')} x1="56" y1="18" x2="32" y2="60" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#f0abfc" stopOpacity="0.85" />
          <stop offset="1" stopColor="#8b5cf6" stopOpacity="0.4" />
        </linearGradient>
        <linearGradient id={id('edge')} x1="8" y1="4" x2="56" y2="60" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#ffffff" stopOpacity="0.95" />
          <stop offset="0.5" stopColor="#ffffff" stopOpacity="0.45" />
          <stop offset="1" stopColor="#f0abfc" stopOpacity="0.8" />
        </linearGradient>
      </defs>

      {/* the outer cube: top, left and right faces */}
      <path d="M32 4 56 18 32 32 8 18Z" fill={`url(#${id('top')})`} />
      <path d="M8 18 32 32V60L8 46Z" fill={`url(#${id('left')})`} />
      <path d="M56 18 32 32V60L56 46Z" fill={`url(#${id('right')})`} />

      {/* the smaller cube inside, drawn as light edges, so the glass reads as having a body */}
      <path d="M32 18 44 25 44 39 32 46 20 39 20 25Z" fill="#ffffff" fillOpacity="0.1" stroke="#ffffff" strokeOpacity="0.35" strokeWidth="0.8" strokeLinejoin="round" />
      <path d="M20 25 32 32 44 25M32 32V46" stroke="#ffffff" strokeOpacity="0.5" strokeWidth="0.8" strokeLinecap="round" />

      {/* the three edges that meet at the near corner are the brightest */}
      <path d="M8 18 32 32 56 18M32 32V60" stroke="#ffffff" strokeOpacity="0.9" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M32 4 56 18V46L32 60 8 46V18Z" stroke={`url(#${id('edge')})`} strokeWidth="1.4" strokeLinejoin="round" />

      {/* a specular streak on the left face and a small sparkle */}
      <path d="M12 22 18 25.5" stroke="#ffffff" strokeOpacity="0.85" strokeWidth="1.6" strokeLinecap="round" />
      <path d="M49 9v6M46 12h6" stroke="#ffffff" strokeOpacity="0.9" strokeWidth="1.2" strokeLinecap="round" />
    </svg>
  )
}
