let webgl: boolean | undefined

/** Whether this browser can start WebGL at all. Cached; false on a server and in tests, where there is no document. */
export function webglAvailable(): boolean {
  if (webgl !== undefined) return webgl
  if (typeof document === 'undefined') return false
  try {
    const canvas = document.createElement('canvas')
    webgl = !!(canvas.getContext('webgl2') || canvas.getContext('webgl'))
  } catch {
    webgl = false
  }
  return webgl
}

export function prefersReducedMotion(): boolean {
  return typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** The 3D cube is its own chunk. Both the page that shows it and the app's start-up call this, so the code is already there when the cube is wanted. */
export const loadCube = () => import('../components/GlassCube')

/** Starts downloading the 3D cube now, if this browser will draw it. Safe to call more than once. */
export function preloadCube(): void {
  if (webglAvailable() && !prefersReducedMotion()) void loadCube()
}
