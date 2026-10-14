import { useEffect } from 'react'
import { streamDocumentEvents } from './api'

/**
 * Subscribes to the workspace's live document events and calls onChange for each one. Reconnects with backoff when the
 * stream drops (deploys, proxies and laptops sleeping all close it), and stops when the workspace changes or the page
 * unmounts. Callers should still poll while documents are processing, so the UI works even if the stream cannot connect.
 */
export function useLiveDocuments(workspaceId: string | undefined, onChange: () => void) {
  useEffect(() => {
    if (!workspaceId) return
    const abort = new AbortController()
    let delay = 1000

    ;(async () => {
      while (!abort.signal.aborted) {
        try {
          await streamDocumentEvents((e) => {
            delay = 1000 // a working connection resets the backoff
            if (e.event === 'document') onChange()
          }, abort.signal)
        } catch {
          if (abort.signal.aborted) return
        }
        await new Promise((r) => setTimeout(r, delay))
        delay = Math.min(delay * 2, 15000)
      }
    })()

    return () => abort.abort()
    // onChange is intentionally excluded: callers pass a fresh closure each render and only the workspace should restart the stream.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workspaceId])
}
