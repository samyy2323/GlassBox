import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { ChevronLeft, Loader2, Wand2 } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { api, ApiError, type AutotuneObjective, type AutotuneRequest } from '../lib/api'
import { OBJECTIVE_LABEL } from '../lib/autotune'
import { queryKeys } from '../lib/queryKeys'
import { useWorkspace } from '../lib/workspace'

function num(v: string): number | undefined {
  return v.trim() === '' ? undefined : Number(v)
}

export default function AutotuneNew() {
  const { current } = useWorkspace()
  const navigate = useNavigate()
  const sets = useQuery({ queryKey: queryKeys.testsets(current?.id), queryFn: api.testsets, enabled: !!current })
  const finals = (sets.data ?? []).filter((t) => t.status === 'FINAL').sort((a, b) => b.createdAt.localeCompare(a.createdAt))

  const [testsetId, setTestsetId] = useState('')
  const [name, setName] = useState('Tune retrieval')
  const [objective, setObjective] = useState<AutotuneObjective>('BEST_QUALITY')
  const [floor, setFloor] = useState('80')
  const [latencyCap, setLatencyCap] = useState('1500')
  const [fullStage, setFullStage] = useState(false)
  const [transformers, setTransformers] = useState(false)
  const [confirmed, setConfirmed] = useState(false)
  const [fullQuestions, setFullQuestions] = useState('20')
  const [stage1, setStage1] = useState('18')
  const [stage2, setStage2] = useState('24')
  const [finalists, setFinalists] = useState('5')
  const [capMinutes, setCapMinutes] = useState('60')
  const [capLlmCalls, setCapLlmCalls] = useState('600')
  const chosen = testsetId || finals[0]?.id || ''

  const request: AutotuneRequest = {
    testsetId: chosen,
    name: name.trim(),
    objective,
    qualityFloor: objective === 'CHEAPEST_ABOVE_FLOOR' ? (num(floor) ?? 80) / 100 : undefined,
    latencyCapMs: objective === 'FASTEST_WITHIN_CAP' ? num(latencyCap) : undefined,
    fullStage,
    transformers: transformers || undefined,
    confirm: confirmed || undefined,
    fullQuestions: fullStage ? num(fullQuestions) : undefined,
    stage1Candidates: num(stage1),
    stage2Candidates: num(stage2),
    finalists: num(finalists),
    capMinutes: num(capMinutes),
    capLlmCalls: num(capLlmCalls),
  }
  const estimate = useQuery({
    queryKey: ['autotune-estimate', JSON.stringify(request)],
    queryFn: () => api.estimateAutotune(request),
    enabled: !!chosen,
    retry: false,
  })
  const create = useMutation({ mutationFn: () => api.createAutotune(request), onSuccess: (a) => navigate(`/autotune/${a.id}`) })
  const e = estimate.data
  const valid = !!chosen && name.trim() !== ''
  const error = create.error instanceof ApiError ? create.error.message : create.error ? 'Could not start the search.' : null
  const field = 'input-glass !py-1.5 text-sm'

  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto w-full max-w-3xl space-y-6 p-4 sm:p-6">
        <header className="flex items-center gap-3">
          <Link to="/autotune" className="btn-ghost !px-2" aria-label="All AutoTune runs"><ChevronLeft className="h-4 w-4" /></Link>
          <div>
            <h1 className="text-[2rem] leading-tight">New AutoTune</h1>
            <p className="text-sm text-muted">The test set is split. Every choice is made on 70% of the questions; only the other 30% is used for the result.</p>
          </div>
        </header>

        <section className="glass space-y-4 rounded-2xl p-5">
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="block space-y-1.5 text-sm font-medium">Name
              <input className="input-glass" value={name} maxLength={120} onChange={(ev) => setName(ev.target.value)} />
            </label>
            <label className="block space-y-1.5 text-sm font-medium">Test set (final versions only)
              <select className="input-glass" value={chosen} onChange={(ev) => setTestsetId(ev.target.value)} disabled={finals.length === 0}>
                {finals.length === 0 && <option value="">No final test set yet</option>}
                {finals.map((t) => <option key={t.id} value={t.id}>{t.name} v{t.version} ({t.stats.accepted + t.stats.edited} questions)</option>)}
              </select>
            </label>
          </div>
          {finals.length === 0 && !sets.isLoading && <p className="text-sm text-muted">AutoTune measures a test set that has been reviewed and finalized. <Link to="/testsets" className="font-medium text-accent underline-offset-4 hover:underline">Open test sets</Link></p>}

          <fieldset className="space-y-2">
            <legend className="text-sm font-medium">What should it optimise?</legend>
            <div className="grid gap-3 sm:grid-cols-3">
              {(Object.keys(OBJECTIVE_LABEL) as AutotuneObjective[]).map((o) => (
                <label key={o} className={`cursor-pointer rounded-xl border p-3 text-sm ${objective === o ? 'border-accent bg-accent/5' : 'border-[var(--line)]'}`}>
                  <input type="radio" className="sr-only" name="objective" checked={objective === o} onChange={() => setObjective(o)} />
                  <span className="block font-semibold">{OBJECTIVE_LABEL[o].title}</span>
                  <span className="mt-0.5 block text-xs text-muted">{OBJECTIVE_LABEL[o].text}</span>
                </label>
              ))}
            </div>
            {objective === 'CHEAPEST_ABOVE_FLOOR' && (
              <label className="block space-y-1 text-xs text-muted">Quality floor (percent)
                <input className={`${field} !w-32`} type="number" min={0} max={100} value={floor} onChange={(ev) => setFloor(ev.target.value)} />
              </label>
            )}
            {objective === 'FASTEST_WITHIN_CAP' && (
              <label className="block space-y-1 text-xs text-muted">Latency cap (milliseconds)
                <input className={`${field} !w-32`} type="number" min={1} value={latencyCap} onChange={(ev) => setLatencyCap(ev.target.value)} />
              </label>
            )}
          </fieldset>

          <label className="flex items-start gap-3 rounded-xl border border-[var(--line)] p-3 text-sm">
            <input type="checkbox" className="mt-1 accent-[rgb(var(--accent-rgb))]" checked={transformers} onChange={(ev) => setTransformers(ev.target.checked)} />
            <span>
              <span className="block font-medium">Also try query transformers</span>
              <span className="block text-xs text-muted">Multi-query, HyDE and decomposition rewrite each question with the model before the search. They add model calls to the retrieval stages, which count against the spend cap; answers to repeated questions come from a cache.</span>
            </span>
          </label>

          <label className="flex items-start gap-3 rounded-xl border border-[var(--line)] p-3 text-sm">
            <input type="checkbox" className="mt-1 accent-[rgb(var(--accent-rgb))]" checked={fullStage} onChange={(ev) => setFullStage(ev.target.checked)} />
            <span>
              <span className="block font-medium">Also measure full answers for the finalists</span>
              <span className="block text-xs text-muted">Adds correctness and faithfulness to the score (50% context recall, 30% correctness, 20% faithfulness). Slow on a small local model; off, the score is context recall alone and no model is called.</span>
              {fullStage && (
                <span className="mt-2 block text-xs text-muted">Questions per part
                  <input className={`${field} ml-2 !w-24`} type="number" min={1} max={200} value={fullQuestions} onChange={(ev) => setFullQuestions(ev.target.value)} aria-label="Questions for full answers" />
                </span>
              )}
            </span>
          </label>
        </section>

        <section className="glass space-y-3 rounded-2xl p-5">
          <h2 className="text-sm font-semibold">Budget and size of the search</h2>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
            <label className="space-y-1 text-xs text-muted">Chunking and embedding candidates<input className={field} type="number" min={1} max={60} value={stage1} onChange={(ev) => setStage1(ev.target.value)} /></label>
            <label className="space-y-1 text-xs text-muted">Retrieval setting candidates<input className={field} type="number" min={1} max={60} value={stage2} onChange={(ev) => setStage2(ev.target.value)} /></label>
            <label className="space-y-1 text-xs text-muted">Finalists<input className={field} type="number" min={1} max={8} value={finalists} onChange={(ev) => setFinalists(ev.target.value)} /></label>
            <label className="space-y-1 text-xs text-muted">Stop after (minutes)<input className={field} type="number" min={1} max={600} value={capMinutes} onChange={(ev) => setCapMinutes(ev.target.value)} /></label>
            <label className="space-y-1 text-xs text-muted">Stop after (model calls)<input className={field} type="number" min={1} value={capLlmCalls} onChange={(ev) => setCapLlmCalls(ev.target.value)} /></label>
          </div>
          <p className="text-xs text-muted">The search stops when the first limit is reached and keeps the best result so far. The baseline (fixed 512-token chunks, the small embedding model, vector search, top 5) is always measured.</p>
        </section>

        <section className="glass space-y-2 rounded-2xl p-5" aria-label="Estimate" aria-live="polite">
          <h2 className="text-sm font-semibold">Estimate</h2>
          {estimate.isLoading && <div className="skeleton h-12" />}
          {estimate.isError && <p className="text-sm text-danger">{estimate.error instanceof ApiError ? estimate.error.message : 'Could not estimate.'}</p>}
          {e && (
            <>
              <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
                <div><dt className="eyebrow">Tune on</dt><dd className="mt-0.5 font-semibold">{e.tuningQuestions} questions</dd></div>
                <div><dt className="eyebrow">Held out</dt><dd className="mt-0.5 font-semibold">{e.heldOutQuestions} questions</dd></div>
                <div><dt className="eyebrow">Searches</dt><dd className="mt-0.5 font-semibold">about {e.retrievalCalls}</dd></div>
                <div><dt className="eyebrow">Model calls</dt><dd className="mt-0.5 font-semibold">{e.llmCalls}</dd></div>
              </dl>
              {e.needsConfirmation && (
                <label className="flex items-center gap-2 text-sm font-medium text-warn"><input type="checkbox" checked={confirmed} onChange={(ev) => setConfirmed(ev.target.checked)} className="accent-[rgb(var(--accent-rgb))]" /> This is above your confirmation threshold ({e.estimatedUsd != null ? `$${e.estimatedUsd.toFixed(2)}` : 'see the estimate'}). Run it anyway.</label>
              )}
              <ul className="list-disc space-y-1 pl-5 text-xs text-muted">{e.notes.map((n) => <li key={n}>{n}</li>)}</ul>
            </>
          )}
        </section>

        {error && <p role="alert" className="text-sm text-danger">{error}</p>}
        <div className="flex justify-end gap-2 pb-6">
          <Link to="/autotune" className="btn-ghost">Cancel</Link>
          <button className="btn-primary" disabled={!valid || create.isPending || (e?.needsConfirmation === true && !confirmed)} onClick={() => create.mutate()}>
            {create.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Wand2 className="h-4 w-4" />} Start the search
          </button>
        </div>
      </div>
    </div>
  )
}
