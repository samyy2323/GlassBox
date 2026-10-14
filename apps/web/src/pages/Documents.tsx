import { useQuery } from '@tanstack/react-query'
import { api } from '../lib/api'

export default function Documents() {
  const ws = useQuery({ queryKey: ['workspaces'], queryFn: api.workspaces })
  const current = ws.data?.[0]

  if (ws.isLoading) return <div className="h-24 animate-pulse rounded-lg bg-slate-200 dark:bg-slate-800" aria-label="Loading" />
  if (ws.isError) return <p role="alert" className="text-red-600">Could not load workspaces.</p>

  return (
    <section className="space-y-4">
      <h1 className="text-lg font-semibold">Documents</h1>
      <div className="card text-center text-sm text-slate-500">
        <p>
          Workspace <strong>{current?.name}</strong> ({current?.role}) has no documents yet.
        </p>
        <p className="mt-1">Uploading arrives in phase P1.</p>
      </div>
    </section>
  )
}
