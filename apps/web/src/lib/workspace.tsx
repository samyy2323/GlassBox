import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, setWorkspaceId, type Workspace } from './api'
import { queryKeys } from './queryKeys'

interface WorkspaceState {
  workspaces: Workspace[]
  current: Workspace | null
  select: (id: string) => void
  loading: boolean
}

const Ctx = createContext<WorkspaceState | null>(null)
const KEY = 'glassbox.workspace'

function remembered(): string | null {
  try {
    return localStorage.getItem(KEY)
  } catch {
    return null
  }
}

export function WorkspaceProvider({ children }: { children: ReactNode }) {
  const list = useQuery({ queryKey: queryKeys.workspaces(), queryFn: api.workspaces })
  const [selected, setSelected] = useState<string | null>(remembered)

  const workspaces = useMemo(() => list.data ?? [], [list.data])
  // The remembered workspace may no longer exist (left, deleted): fall back to the first one.
  const current = workspaces.find((w) => w.id === selected) ?? workspaces[0] ?? null

  // Set synchronously during render so children's queries (which run in effects) already send the right header.
  setWorkspaceId(current?.id ?? null)
  useEffect(() => () => setWorkspaceId(null), [])

  const value = useMemo<WorkspaceState>(
    () => ({
      workspaces,
      current,
      loading: list.isLoading,
      select: (id) => {
        setSelected(id)
        try {
          localStorage.setItem(KEY, id)
        } catch {
          /* storage unavailable: selection just is not remembered */
        }
      },
    }),
    [workspaces, current, list.isLoading],
  )
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useWorkspace(): WorkspaceState {
  const v = useContext(Ctx)
  if (!v) throw new Error('useWorkspace outside WorkspaceProvider')
  return v
}
