export type SplashPhase = 'show' | 'fade' | 'done'

/** Total time the splash screen is on screen, fade-out included. */
export const SPLASH_MS = 3000
/** The last part of that time is the fade-out. */
export const SPLASH_FADE_MS = 400

/** Where the splash is after `elapsedMs`: fully visible, fading out, or gone. Pure so the 3 second promise can be tested. */
export function splashPhase(elapsedMs: number, total = SPLASH_MS, fade = SPLASH_FADE_MS): SplashPhase {
  if (elapsedMs >= total) return 'done'
  return elapsedMs >= total - fade ? 'fade' : 'show'
}
