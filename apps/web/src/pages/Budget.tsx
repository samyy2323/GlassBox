import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Loader2, Trash2, Wallet } from 'lucide-react'
import { api, ApiError, type ModelPrice } from '../lib/api'
import { estimatorError, formatTokens, formatUsd, monthProgress } from '../lib/budget'
import { queryKeys } from '../lib/queryKeys'
import { useWorkspace } from '../lib/workspace'

function num(v: string): number | null {
  return v.trim() === '' ? null : Number(v)
}

export default function Budget() {
  const { current } = useWorkspace()
  const qc = useQueryClient()
  const isOwner = current?.role === 'OWNER'
  const overview = useQuery({ queryKey: queryKeys.budget(current?.id), queryFn: api.budget, enabled: !!current })
  const refresh = () => qc.invalidateQueries({ queryKey: queryKeys.budget(current?.id) })
  const [monthly, setMonthly] = useState<string | null>(null)
  const [perRun, setPerRun] = useState<string | null>(null)
  const [confirmAbove, setConfirmAbove] = useState<string | null>(null)
  const [price, setPrice] = useState<ModelPrice>({ model: '', promptUsdPerMillion: 0, completionUsdPerMillion: 0 })
  const o = overview.data
  const save = useMutation({
    mutationFn: () => api.setBudget({ monthlyUsd: num(monthly ?? String(o?.budget.monthlyUsd ?? '')), perRunUsd: num(perRun ?? String(o?.budget.perRunUsd ?? '')), confirmAboveUsd: num(confirmAbove ?? String(o?.budget.confirmAboveUsd ?? '')) }),
    onSuccess: refresh,
  })
  const addPrice = useMutation({ mutationFn: () => api.setPrice(price), onSuccess: () => { setPrice({ model: '', promptUsdPerMillion: 0, completionUsdPerMillion: 0 }); refresh() } })
  const removePrice = useMutation({ mutationFn: (model: string) => api.deletePrice(model), onSuccess: refresh })

  if (overview.isPending) return <div className="mx-auto w-full max-w-3xl space-y-4 p-6" aria-label="Loading budget"><div className="skeleton h-10 w-60" /><div className="skeleton h-40" /></div>
  if (overview.isError || !o) return <p role="alert" className="p-6 text-sm text-red-500">Could not load the budget.</p>
  const progress = monthProgress(o.spentThisMonthUsd, o.budget.monthlyUsd)
  const field = 'input-glass !py-1.5 text-sm'
  const error = [save.error, addPrice.error, removePrice.error].map((e) => (e instanceof ApiError ? e.message : e ? 'That did not work.' : null)).find(Boolean)

  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto w-full max-w-3xl space-y-5 p-4 sm:p-6">
        <header>
          <h1 className="flex items-center gap-2 text-[1.65rem] font-semibold tracking-tight"><Wallet className="h-6 w-6 text-accent" /> Budget</h1>
          <p className="mt-1 text-sm text-muted">What runs may cost. A run is refused when its estimate passes a limit, and a running one stops when its spend passes 120% of its cap.</p>
        </header>

        <section className="glass space-y-3 rounded-2xl p-5">
          <h2 className="text-sm font-semibold">This month</h2>
          <p className="text-2xl font-semibold tabular-nums">{formatUsd(o.spentThisMonthUsd)} <span className="text-sm font-normal text-muted">and {formatTokens(o.tokensThisMonth)} tokens</span></p>
          {progress != null && <div className="h-2 overflow-hidden rounded-full bg-fg/10" role="progressbar" aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100}><div className={`h-full rounded-full ${progress >= 90 ? 'bg-red-500' : 'bg-accent'}`} style={{ width: `${progress}%` }} /></div>}
          <div className="grid gap-3 sm:grid-cols-3">
            <label className="space-y-1 text-xs text-muted">Monthly budget (dollars)<input className={field} type="number" min={0} disabled={!isOwner} value={monthly ?? o.budget.monthlyUsd ?? ''} placeholder="no limit" onChange={(e) => setMonthly(e.target.value)} /></label>
            <label className="space-y-1 text-xs text-muted">Cap for one run (dollars)<input className={field} type="number" min={0} disabled={!isOwner} value={perRun ?? o.budget.perRunUsd ?? ''} placeholder="no limit" onChange={(e) => setPerRun(e.target.value)} /></label>
            <label className="space-y-1 text-xs text-muted">Ask to confirm above (dollars)<input className={field} type="number" min={0} disabled={!isOwner} value={confirmAbove ?? o.budget.confirmAboveUsd ?? ''} placeholder="never" onChange={(e) => setConfirmAbove(e.target.value)} /></label>
          </div>
          {isOwner ? <button className="btn-primary" onClick={() => save.mutate()} disabled={save.isPending}>{save.isPending && <Loader2 className="h-4 w-4 animate-spin" />} Save limits</button> : <p className="text-xs text-muted">Only an owner can change the limits.</p>}
          <p className="text-xs text-muted">A local model costs nothing, so its dollar figures are zero; runs are still counted in tokens and model calls.</p>
        </section>

        <section className="glass space-y-3 rounded-2xl p-5">
          <h2 className="text-sm font-semibold">Prices</h2>
          <p className="text-xs text-muted">Dollars per million tokens, per model. A model without a row uses the default ({formatUsd(o.defaults.promptUsdPerMillion)} prompt, {formatUsd(o.defaults.completionUsdPerMillion)} completion).</p>
          <ul className="space-y-1.5 text-sm">
            {o.prices.map((p) => (
              <li key={p.model} className="flex items-center gap-3"><span className="min-w-0 flex-1 truncate font-medium">{p.model}</span><span className="tabular-nums text-muted">{p.promptUsdPerMillion} / {p.completionUsdPerMillion}</span>
                {isOwner && <button className="btn-ghost !p-1.5" aria-label={`Remove the price of ${p.model}`} onClick={() => removePrice.mutate(p.model)}><Trash2 className="h-4 w-4" /></button>}</li>
            ))}
            {o.prices.length === 0 && <li className="text-muted">No prices set.</li>}
          </ul>
          {isOwner && (
            <div className="flex flex-wrap items-end gap-3">
              <label className="space-y-1 text-xs text-muted">Model<input className={`${field} !w-48`} value={price.model} onChange={(e) => setPrice({ ...price, model: e.target.value })} placeholder="gpt-4o-mini" /></label>
              <label className="space-y-1 text-xs text-muted">Prompt<input className={`${field} !w-28`} type="number" min={0} value={price.promptUsdPerMillion} onChange={(e) => setPrice({ ...price, promptUsdPerMillion: Number(e.target.value) })} /></label>
              <label className="space-y-1 text-xs text-muted">Completion<input className={`${field} !w-28`} type="number" min={0} value={price.completionUsdPerMillion} onChange={(e) => setPrice({ ...price, completionUsdPerMillion: Number(e.target.value) })} /></label>
              <button className="btn-ghost !border !border-[var(--line)]" disabled={!price.model.trim() || addPrice.isPending} onClick={() => addPrice.mutate()}>Set price</button>
            </div>
          )}
        </section>

        <section className="glass space-y-3 rounded-2xl p-5">
          <h2 className="text-sm font-semibold">Estimate against actual</h2>
          <p className="text-sm">{estimatorError(o.tokenError, o.measuredRuns)}</p>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[32rem] text-left text-sm">
              <thead><tr className="text-xs text-muted"><th className="py-1.5 pr-3 font-medium">Run</th><th className="px-2 font-medium">Estimated</th><th className="px-2 font-medium">Actual</th><th className="px-2 font-medium">When</th></tr></thead>
              <tbody>
                {o.recent.map((r) => (
                  <tr key={`${r.kind}-${r.refId}`} className="border-t border-[var(--line)]">
                    <th scope="row" className="py-1.5 pr-3 font-medium">{r.kind === 'AUTOTUNE' ? 'AutoTune' : 'Experiment'}{r.aborted && <span className="ml-2 chip !text-warn">stopped by its cap</span>}</th>
                    <td className="px-2 tabular-nums">{formatUsd(r.estimatedUsd)}, {formatTokens(r.estimatedTokens)} tokens, {r.estimatedCalls} calls</td>
                    <td className="px-2 tabular-nums">{formatUsd(r.actualUsd)}, {formatTokens(r.actualTokens)} tokens, {r.actualCalls} calls</td>
                    <td className="px-2 text-xs text-muted">{new Date(r.createdAt).toLocaleDateString()}</td>
                  </tr>
                ))}
                {o.recent.length === 0 && <tr><td colSpan={4} className="py-3 text-muted">Nothing has run yet.</td></tr>}
              </tbody>
            </table>
          </div>
        </section>
        {error && <p role="alert" className="text-sm text-red-500">{error}</p>}
      </div>
    </div>
  )
}
