/**
 * One pointer listener for the whole app: while the pointer is over an element with the class `glass-interactive`, that element gets `--mx` and `--my`
 * (the pointer position inside it, in pixels), which the stylesheet turns into a soft light that follows the cursor. Work is limited to one update per frame.
 */
export function installPointerLight(root: Document = document): () => void {
  let frame = 0
  let pending: { el: HTMLElement; x: number; y: number } | null = null

  const flush = () => {
    frame = 0
    if (!pending) return
    const { el, x, y } = pending
    el.style.setProperty('--mx', `${x}px`)
    el.style.setProperty('--my', `${y}px`)
    pending = null
  }

  const onMove = (e: PointerEvent) => {
    if (e.pointerType === 'touch') return
    const target = e.target instanceof Element ? e.target.closest<HTMLElement>('.glass-interactive') : null
    if (!target) return
    const box = target.getBoundingClientRect()
    pending = { el: target, x: e.clientX - box.left, y: e.clientY - box.top }
    if (!frame) frame = requestAnimationFrame(flush)
  }

  root.addEventListener('pointermove', onMove, { passive: true })
  return () => {
    root.removeEventListener('pointermove', onMove)
    if (frame) cancelAnimationFrame(frame)
  }
}
