/**
 * The soft, slowly drifting grey light behind every page. It gives the frosted glass something to blur, which is what makes
 * the panels read as glass. Monochrome by design; pure decoration, so hidden from assistive tech and still under reduced motion.
 */
export function Aurora() {
  return (
    <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden" style={{ background: 'var(--bg)' }}>
      <div
        className="absolute -left-[10vw] -top-[20vh] h-[60vw] max-h-[780px] w-[60vw] max-w-[780px] animate-orb-a rounded-full blur-[120px]"
        style={{ background: 'var(--orb-1)', opacity: 'var(--orb-opacity)' }}
      />
      <div
        className="absolute -right-[12vw] top-[6vh] h-[52vw] max-h-[680px] w-[52vw] max-w-[680px] animate-orb-b rounded-full blur-[130px]"
        style={{ background: 'var(--orb-2)', opacity: 'var(--orb-opacity)' }}
      />
      <div
        className="absolute -bottom-[24vh] left-[20vw] h-[56vw] max-h-[720px] w-[56vw] max-w-[720px] animate-orb-c rounded-full blur-[140px]"
        style={{ background: 'var(--orb-3)', opacity: 'calc(var(--orb-opacity) * 0.7)' }}
      />
      {/* a fine dot grid, barely there, so large empty areas feel intentional */}
      <div
        className="absolute inset-0"
        style={{
          backgroundImage: 'radial-gradient(rgb(var(--fg-rgb) / 0.07) 1px, transparent 1px)',
          backgroundSize: '26px 26px',
          maskImage: 'radial-gradient(ellipse at 50% 30%, black 20%, transparent 75%)',
          WebkitMaskImage: 'radial-gradient(ellipse at 50% 30%, black 20%, transparent 75%)',
        }}
      />
    </div>
  )
}
