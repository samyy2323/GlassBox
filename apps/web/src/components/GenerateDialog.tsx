import { useMemo, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Loader2, Sparkles } from 'lucide-react'
import { api, ApiError, type TestSet } from '../lib/api'
import { queryKeys } from '../lib/queryKeys'
import { apportion, cleanMix, estimateCalls, GENERATABLE, setTypeCount, TYPE_LABEL, type Mix } from '../lib/testsets'
import { useWorkspace } from '../lib/workspace'
import { Dialog } from './Dialog'

const DEFAULT_MIX: Mix = { FACTUAL: 50, NUMERIC: 15, MULTI_HOP: 10, COMPARISON: 15, UNANSWERABLE: 10 }

/** One colour per type, from the prism and a neutral for the type that has no answer. */
const BAR_COLOUR: Record<string, string> = { FACTUAL: '#67e8f9', NUMERIC: '#7dd3fc', MULTI_HOP: '#a78bfa', COMPARISON: '#f0abfc', UNANSWERABLE: '#94a3b8' }

export function GenerateDialog({ existingNames, onClose, onCreated }: { existingNames: string[]; onClose: () => void; onCreated: (t: TestSet) => void }) {
  const { current } = useWorkspace()
  const docs = useQuery({ queryKey: queryKeys.documents(current?.id), queryFn: () => api.documents(), enabled: !!current })
  const ready = useMemo(() => (docs.data?.items ?? []).filter((d) => d.status === 'READY'), [docs.data])

  const [name, setName] = useState(() => {
    let n = existingNames.length + 1
    while (existingNames.includes(`Test set ${n}`)) n++
    return `Test set ${n}`
  })
  const [count, setCount] = useState(50)
  // The shares are what the person chose; the counts shown are those shares applied to the number of questions, so they always add up to it.
  // Changing the number never loses the shares: going from 50 to 10 and back gives the original split again.
  const [shares, setShares] = useState<Mix>(DEFAULT_MIX)
  const mix = useMemo(() => apportion(count, shares), [count, shares])
  const [excluded, setExcluded] = useState<Set<string>>(new Set())

  const chosen = ready.filter((d) => !excluded.has(d.id))
  const create = useMutation({
    mutationFn: () =>
      api.generateTestset({
        name: name.trim(),
        count,
        mix: cleanMix(mix),
        documentIds: excluded.size > 0 ? chosen.map((d) => d.id) : undefined,
      }),
    onSuccess: (r) => onCreated(r.testset),
  })
  const valid = name.trim().length > 0 && count >= 1 && count <= 200 && chosen.length > 0 && Object.values(mix).some((v) => (v ?? 0) > 0)
  const error = create.error instanceof ApiError ? create.error.message : create.error ? 'Could not start the generation.' : null

  return (
    <Dialog title="Generate a test set" onClose={onClose}>
      <form
        className="space-y-5"
        onSubmit={(e) => {
          e.preventDefault()
          if (valid && !create.isPending) create.mutate()
        }}
      >
        <p className="text-sm text-muted">Questions are written by the model from passages of your own documents, then flagged for quality. You review them before anything is measured.</p>

        <div className="grid gap-4 sm:grid-cols-[1fr_8rem]">
          <label className="block space-y-1.5 text-sm font-medium">
            Name
            <input className="input-glass" value={name} maxLength={120} onChange={(e) => setName(e.target.value)} required />
          </label>
          <label className="block space-y-1.5 text-sm font-medium">
            Questions
            <input className="input-glass" type="number" min={1} max={200} value={count} onChange={(e) => setCount(Number(e.target.value))} required />
          </label>
        </div>

        <fieldset className="space-y-2">
          <legend className="text-sm font-medium">Mix of question types <span className="numeric font-normal text-muted">({Object.values(mix).reduce<number>((a, b) => a + (b ?? 0), 0)} of {count})</span></legend>
          <div className="flex h-1.5 overflow-hidden rounded-full bg-fg/10" role="img" aria-label={GENERATABLE.map((t) => `${TYPE_LABEL[t]} ${mix[t] ?? 0}`).join(', ')}>
            {GENERATABLE.map((t) => (
              <span key={t} title={`${TYPE_LABEL[t]}: ${mix[t] ?? 0}`} className="h-full transition-[width] duration-300" style={{ width: `${count > 0 ? ((mix[t] ?? 0) / count) * 100 : 0}%`, background: BAR_COLOUR[t] }} />
            ))}
          </div>
          <div className="grid grid-cols-2 gap-3 pt-1 sm:grid-cols-5">
            {GENERATABLE.map((t) => (
              <label key={t} className="block space-y-1 text-xs text-muted">
                <span className="flex items-center gap-1.5"><span aria-hidden className="h-2 w-2 rounded-full" style={{ background: BAR_COLOUR[t] }} />{TYPE_LABEL[t]}</span>
                <input
                  className="input-glass !py-1.5"
                  type="number"
                  min={0}
                  max={count}
                  value={mix[t] ?? 0}
                  onChange={(e) => setShares(setTypeCount(count, mix, t, Number(e.target.value)))}
                  aria-label={`${TYPE_LABEL[t]} questions`}
                />
              </label>
            ))}
          </div>
          <p className="text-xs text-muted">These add up to the number of questions: change the total and every type follows, change one type and the others adjust to keep it. Multi-hop and comparison questions need facts from two passages. Unanswerable questions ask for a detail the documents do not state; they test whether the system admits it does not know. A model checks each against what the search finds, and flags the ones it could answer.</p>
        </fieldset>

        <fieldset className="space-y-2">
          <legend className="text-sm font-medium">Documents ({chosen.length} of {ready.length})</legend>
          {docs.isLoading ? (
            <div className="skeleton h-20" />
          ) : ready.length === 0 ? (
            <p className="text-sm text-muted">No document is ready yet. Upload some in the Library first.</p>
          ) : (
            <ul className="max-h-40 space-y-1 overflow-y-auto rounded-xl border border-[var(--line)] p-2">
              {ready.map((d) => (
                <li key={d.id}>
                  <label className="flex cursor-pointer items-center gap-2.5 rounded-lg px-2 py-1 text-sm hover:bg-[var(--hover)]">
                    <input
                      type="checkbox"
                      className="accent-[rgb(var(--accent-rgb))]"
                      checked={!excluded.has(d.id)}
                      onChange={(e) => {
                        const next = new Set(excluded)
                        if (e.target.checked) next.delete(d.id)
                        else next.add(d.id)
                        setExcluded(next)
                      }}
                    />
                    <span className="truncate">{d.title}</span>
                  </label>
                </li>
              ))}
            </ul>
          )}
        </fieldset>

        <p className="rounded-xl bg-accent/10 px-3 py-2 text-xs text-accent">
          About {estimateCalls(count)} model calls: one to write each question and two quality checks. It runs in the background, one question at a time, so a small local model takes a while; you can leave this page.
        </p>
        {error && <p role="alert" className="text-sm text-red-500">{error}</p>}

        <div className="flex justify-end gap-2">
          <button type="button" className="btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn-primary" disabled={!valid || create.isPending}>
            {create.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />} Generate
          </button>
        </div>
      </form>
    </Dialog>
  )
}
