import type { DocStatus } from '../lib/api'

// Text stays in the theme's own black or white; only the small dot carries meaning, because "failed" has to be unmistakable.
const STYLE: Record<DocStatus, { label: string; dot: string }> = {
  UPLOADED: { label: 'Queued', dot: 'bg-fg/40' },
  PROCESSING: { label: 'Processing', dot: 'bg-amber-400 animate-pulse' },
  READY: { label: 'Ready', dot: 'bg-emerald-400' },
  FAILED: { label: 'Failed', dot: 'bg-red-500' },
  FAILED_UNSUPPORTED: { label: 'Unsupported', dot: 'bg-red-500' },
}

export function StatusChip({ status, title }: { status: DocStatus; title?: string }) {
  const s = STYLE[status]
  return (
    <span className="chip" title={title}>
      <span className={`h-1.5 w-1.5 rounded-full ${s.dot}`} aria-hidden />
      {s.label}
    </span>
  )
}
