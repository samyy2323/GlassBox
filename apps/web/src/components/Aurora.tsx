/**
 * The page background (behind everything): three slowly drifting colour orbs, a dot grid that fades out toward the bottom, and a grain layer that stops the
 * gradients from banding. Pure CSS (no JavaScript, no canvas), `position: fixed`, and it is not painted at all for people who ask for reduced transparency.
 */
export function Aurora() {
  return (
    <div className="aurora" aria-hidden>
      <span className="aurora-orb a" />
      <span className="aurora-orb b" />
      <span className="aurora-orb c" />
      <span className="aurora-grid" />
      <span className="aurora-grain" />
    </div>
  )
}
