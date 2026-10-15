import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { ThumbsDown, ThumbsUp } from 'lucide-react'
import { api } from '../lib/api'

/** Rate an answer. A thumbs-down asks what was wrong (optional) and lands in the feedback inbox, where it can become a test question. */
export function Thumbs({ traceId }: { traceId: string }) {
  const [rating, setRating] = useState<'UP' | 'DOWN' | null>(null)
  const [asking, setAsking] = useState(false)
  const [comment, setComment] = useState('')
  const send = useMutation({ mutationFn: (r: { rating: 'UP' | 'DOWN'; comment?: string }) => api.giveFeedback(traceId, r.rating, r.comment), onSuccess: (_, r) => { setRating(r.rating); setAsking(false) } })

  return (
    <span className="inline-flex flex-wrap items-center gap-0.5">
      <button className={`btn-ghost !px-2 !py-1 ${rating === 'UP' ? 'text-ok' : ''}`} aria-label="Good answer" aria-pressed={rating === 'UP'} disabled={send.isPending} onClick={() => send.mutate({ rating: 'UP' })}><ThumbsUp className="h-3.5 w-3.5" /></button>
      <button className={`btn-ghost !px-2 !py-1 ${rating === 'DOWN' ? 'text-red-500' : ''}`} aria-label="Bad answer" aria-pressed={rating === 'DOWN'} disabled={send.isPending} onClick={() => (rating === 'DOWN' ? null : setAsking(true))}><ThumbsDown className="h-3.5 w-3.5" /></button>
      {asking && (
        <span className="ml-1 flex items-center gap-1.5">
          <input className="input-glass !w-56 !py-1 text-xs" autoFocus placeholder="What was wrong? (optional)" value={comment} maxLength={2000} onChange={(e) => setComment(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') send.mutate({ rating: 'DOWN', comment }) }} />
          <button className="btn-ghost !px-2 !py-1 text-xs" onClick={() => send.mutate({ rating: 'DOWN', comment })}>Send</button>
        </span>
      )}
      {rating === 'DOWN' && !asking && <span className="ml-1 text-xs">Thanks. It is in the feedback inbox.</span>}
      {send.isError && <span role="alert" className="ml-1 text-xs text-red-500">Could not send.</span>}
    </span>
  )
}
