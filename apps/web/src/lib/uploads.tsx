import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from './api'
import { MAX_FILE_BYTES } from './formats'
import { queryKeys } from './queryKeys'
import { useWorkspace } from './workspace'

export interface UploadItem {
  key: number
  name: string
  state: 'queued' | 'uploading' | 'done' | 'duplicate' | 'error'
  message?: string
}

interface UploadsState {
  items: UploadItem[]
  enqueue: (files: File[]) => void
  dismiss: (key: number) => void
  clearFinished: () => void
}

const Ctx = createContext<UploadsState | null>(null)

/**
 * One upload queue for the whole app, so a batch keeps going while the user moves between pages, and the composer and
 * the library share the same progress panel. Files go up one at a time: the server ingests them in parallel in the background.
 */
export function UploadsProvider({ children }: { children: ReactNode }) {
  const qc = useQueryClient()
  const { current } = useWorkspace()
  const [items, setItems] = useState<UploadItem[]>([])
  const nextKey = useRef(0)
  const pending = useRef<{ key: number; file: File }[]>([])
  const running = useRef(false)
  const workspace = useRef<string | undefined>(undefined)
  workspace.current = current?.id

  const patch = useCallback((key: number, p: Partial<UploadItem>) => setItems((all) => all.map((i) => (i.key === key ? { ...i, ...p } : i))), [])

  const pump = useCallback(async () => {
    if (running.current) return
    running.current = true
    while (pending.current.length) {
      const { key, file } = pending.current.shift()!
      patch(key, { state: 'uploading' })
      if (file.size > MAX_FILE_BYTES) {
        patch(key, { state: 'error', message: 'Larger than 25 MB' })
        continue
      }
      try {
        const r = await api.upload(file)
        patch(key, r.duplicate ? { state: 'duplicate', message: 'Already in this workspace' } : { state: 'done' })
      } catch (e) {
        patch(key, { state: 'error', message: e instanceof ApiError ? e.message : 'Upload failed' })
      }
      void qc.invalidateQueries({ queryKey: queryKeys.documents(workspace.current) })
    }
    running.current = false
  }, [patch, qc])

  const enqueue = useCallback(
    (files: File[]) => {
      const added = files.map((file) => ({ key: nextKey.current++, file }))
      pending.current.push(...added)
      setItems((all) => [...added.map((a) => ({ key: a.key, name: a.file.name, state: 'queued' as const })), ...all].slice(0, 30))
      void pump()
    },
    [pump],
  )

  const value = useMemo<UploadsState>(
    () => ({
      items,
      enqueue,
      dismiss: (key) => setItems((all) => all.filter((i) => i.key !== key)),
      clearFinished: () => setItems((all) => all.filter((i) => i.state === 'queued' || i.state === 'uploading')),
    }),
    [items, enqueue],
  )
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useUploads(): UploadsState {
  const v = useContext(Ctx)
  if (!v) throw new Error('useUploads outside UploadsProvider')
  return v
}
