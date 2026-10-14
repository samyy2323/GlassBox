import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { ChevronLeft, FlaskConical, Loader2, Plus, X } from 'lucide-react'
import { Link, useNavigate } from 'react-router-dom'
import { api, ApiError, type ConfigInput, type ExperimentMode } from '../lib/api'
import { PRESETS, uniquePreset } from '../lib/experiments'
import { queryKeys } from '../lib/queryKeys'
import { useWorkspace } from '../lib/workspace'

const MAX_CONFIGS = 8

function num(v: string): number | undefined {
  return v.trim() === '' ? undefined : Number(v)
}

function ConfigCard({ config, index, onChange, onRemove, removable }: { config: ConfigInput; index: number; onChange: (c: ConfigInput) => void; onRemove: () => void; removable: boolean }) {
  const field = 'input-glass !py-1.5 text-sm'
  return (
    <li className="glass rounded-2xl p-4">
      <div className="flex items-center gap-3">
        <input className={`${field} !w-auto min-w-0 flex-1 font-semibold`} aria-label={`Name of configuration ${index + 1}`} value={config.name} maxLength={80} onChange={(e) => onChange({ ...config, name: e.target.value })} />
        {index === 0 && <span className="chip" title="Every other configuration is compared with this one">Baseline</span>}
        {removable && <button type="button" className="btn-ghost !p-1.5" aria-label={`Remove ${config.name}`} onClick={onRemove}><X className="h-4 w-4" /></button>}
      </div>
      <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <label className="space-y-1 text-xs text-muted">Search
          <select className={field} value={config.mode ?? 'HYBRID'} onChange={(e) => onChange({ ...config, mode: e.target.value as ConfigInput['mode'] })}>
            <option value="VECTOR">Vector</option><option value="KEYWORD">Keyword</option><option value="HYBRID">Hybrid</option>
          </select>
        </label>
        <label className="space-y-1 text-xs text-muted">Reranker
          <select className={field} value={config.reranker ?? 'NONE'} onChange={(e) => onChange({ ...config, reranker: e.target.value as ConfigInput['reranker'] })}>
            <option value="NONE">None</option><option value="CROSS_ENCODER">Cross-encoder</option>
          </select>
        </label>
        <label className="space-y-1 text-xs text-muted">Top N
          <input className={field} type="number" min={1} max={50} value={config.topN ?? ''} placeholder="5" onChange={(e) => onChange({ ...config, topN: num(e.target.value) })} />
        </label>
        <label className="space-y-1 text-xs text-muted">Context budget
          <input className={field} type="number" min={100} max={32000} step={100} value={config.tokenBudget ?? ''} placeholder="2000" onChange={(e) => onChange({ ...config, tokenBudget: num(e.target.value) })} />
        </label>
        {(config.mode ?? 'HYBRID') === 'HYBRID' && (
          <>
            <label className="space-y-1 text-xs text-muted">Vector weight
              <input className={field} type="number" min={0} max={10} step={0.1} value={config.wVec ?? ''} placeholder="1" onChange={(e) => onChange({ ...config, wVec: num(e.target.value) })} />
            </label>
            <label className="space-y-1 text-xs text-muted">Keyword weight
              <input className={field} type="number" min={0} max={10} step={0.1} value={config.wKw ?? ''} placeholder="1" onChange={(e) => onChange({ ...config, wKw: num(e.target.value) })} />
            </label>
            <label className="space-y-1 text-xs text-muted">RRF k
              <input className={field} type="number" min={1} max={1000} value={config.rrfK ?? ''} placeholder="60" onChange={(e) => onChange({ ...config, rrfK: num(e.target.value) })} />
            </label>
            <label className="space-y-1 text-xs text-muted">Candidates
              <input className={field} type="number" min={1} max={200} value={config.kFused ?? ''} placeholder="50" onChange={(e) => onChange({ ...config, kFused: num(e.target.value) })} />
            </label>
          </>
        )}
      </div>
    </li>
  )
}

export default function ExperimentNew() {
  const { current } = useWorkspace()
  const navigate = useNavigate()
  const sets = useQuery({ queryKey: queryKeys.testsets(current?.id), queryFn: api.testsets, enabled: !!current })
  const finals = (sets.data ?? []).filter((t) => t.status === 'FINAL').sort((a, b) => b.createdAt.localeCompare(a.createdAt))

  const [testsetId, setTestsetId] = useState('')
  const [mode, setMode] = useState<ExperimentMode>('RETRIEVAL')
  const [name, setName] = useState('Vector vs hybrid')
  const [limit, setLimit] = useState('')
  const [configs, setConfigs] = useState<ConfigInput[]>([uniquePreset('vector', []), uniquePreset('hybrid', ['Vector'])])
  const chosen = testsetId || finals[0]?.id || ''
  const questionLimit = limit.trim() === '' || Number(limit) < 1 ? undefined : Math.floor(Number(limit))

  const estimate = useQuery({
    queryKey: ['experiment-estimate', chosen, mode, limit, JSON.stringify(configs)],
    queryFn: () => api.estimateExperiment(chosen, mode, configs, questionLimit),
    enabled: !!chosen && configs.length > 0 && configs.every((c) => c.name.trim() !== ''),
    retry: false,
  })
  const create = useMutation({
    mutationFn: () => api.createExperiment(chosen, name.trim(), mode, configs, questionLimit),
    onSuccess: (e) => navigate(`/experiments/${e.id}`),
  })
  const names = configs.map((c) => c.name.trim())
  const duplicate = new Set(names).size !== names.length
  const valid = !!chosen && name.trim() !== '' && configs.length > 0 && names.every((n) => n !== '') && !duplicate
  const e = estimate.data
  const error = create.error instanceof ApiError ? create.error.message : create.error ? 'Could not start the experiment.' : null

  return (
    <div className="min-h-0 flex-1 overflow-y-auto">
      <div className="mx-auto w-full max-w-3xl space-y-6 p-4 sm:p-6">
        <header className="flex items-center gap-3">
          <Link to="/experiments" className="btn-ghost !px-2" aria-label="All experiments"><ChevronLeft className="h-4 w-4" /></Link>
          <div>
            <h1 className="text-[1.5rem] font-semibold tracking-tight">New experiment</h1>
            <p className="text-sm text-muted">The first configuration is the baseline; every other one is compared with it.</p>
          </div>
        </header>

        <section className="glass space-y-4 rounded-2xl p-5">
          <div className="grid gap-4 sm:grid-cols-2">
            <label className="block space-y-1.5 text-sm font-medium">Name
              <input className="input-glass" value={name} maxLength={120} onChange={(e) => setName(e.target.value)} />
            </label>
            <label className="block space-y-1.5 text-sm font-medium">Test set (final versions only)
              <select className="input-glass" value={chosen} onChange={(e) => setTestsetId(e.target.value)} disabled={finals.length === 0}>
                {finals.length === 0 && <option value="">No final test set yet</option>}
                {finals.map((t) => <option key={t.id} value={t.id}>{t.name} v{t.version} ({t.stats.accepted + t.stats.edited} questions)</option>)}
              </select>
            </label>
          </div>
          {finals.length === 0 && !sets.isLoading && <p className="text-sm text-muted">An experiment measures a test set that has been reviewed and finalized. <Link to="/testsets" className="font-medium text-accent underline-offset-4 hover:underline">Open test sets</Link></p>}
          <fieldset className="space-y-2">
            <legend className="text-sm font-medium">What to measure</legend>
            <div className="grid gap-3 sm:grid-cols-2">
              {([['RETRIEVAL', 'Retrieval only', 'Does the evidence reach the prompt? Seconds, no model tokens.'], ['FULL', 'Full answers', 'Also writes each answer, checks every statement, and has a judge grade it. Slow on a small local model.']] as const).map(([value, title, text]) => (
                <label key={value} className={`cursor-pointer rounded-xl border p-3 text-sm ${mode === value ? 'border-accent bg-accent/5' : 'border-[var(--line)]'}`}>
                  <input type="radio" className="sr-only" name="mode" checked={mode === value} onChange={() => setMode(value)} />
                  <span className="block font-semibold">{title}</span>
                  <span className="mt-0.5 block text-xs text-muted">{text}</span>
                </label>
              ))}
            </div>
          </fieldset>
          <label className="block space-y-1.5 text-sm font-medium">Only measure a sample of the questions (optional)
            <input className="input-glass !w-40" type="number" min={1} value={limit} placeholder="all" onChange={(e) => setLimit(e.target.value)} />
            <span className="block text-xs font-normal text-muted">A fixed random sample, the same for every run. Useful for full answers, which are slow on a small local model; fewer questions mean wider intervals.</span>
          </label>
        </section>

        <section className="space-y-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 className="text-sm font-semibold">Configurations ({configs.length} of {MAX_CONFIGS})</h2>
            <div className="flex flex-wrap gap-1.5">
              {PRESETS.map((p) => (
                <button key={p.id} type="button" className="btn-ghost !border !border-[var(--line)]" title={p.description} disabled={configs.length >= MAX_CONFIGS}
                  onClick={() => setConfigs([...configs, uniquePreset(p.id, names)])}>
                  <Plus className="h-3.5 w-3.5" /> {p.title}
                </button>
              ))}
            </div>
          </div>
          <ul className="space-y-3">
            {configs.map((c, i) => (
              <ConfigCard key={i} config={c} index={i} removable={configs.length > 1}
                onChange={(next) => setConfigs(configs.map((x, k) => (k === i ? next : x)))} onRemove={() => setConfigs(configs.filter((_, k) => k !== i))} />
            ))}
          </ul>
          {duplicate && <p role="alert" className="text-sm text-red-500">Two configurations have the same name.</p>}
        </section>

        <section className="glass space-y-2 rounded-2xl p-5" aria-label="Estimate" aria-live="polite">
          <h2 className="text-sm font-semibold">Estimate</h2>
          {estimate.isLoading && <div className="skeleton h-12" />}
          {estimate.isError && <p className="text-sm text-red-500">{estimate.error instanceof ApiError ? estimate.error.message : 'Could not estimate.'}</p>}
          {e && (
            <>
              <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
                <div><dt className="eyebrow">Questions</dt><dd className="mt-0.5 font-semibold">{e.questions}</dd></div>
                <div><dt className="eyebrow">Runs</dt><dd className="mt-0.5 font-semibold">{e.runs}</dd></div>
                <div><dt className="eyebrow">Model calls</dt><dd className="mt-0.5 font-semibold">{e.llmCalls}</dd></div>
                <div><dt className="eyebrow">Cost</dt><dd className="mt-0.5 font-semibold">{e.estimatedUsd > 0 ? `$${e.estimatedUsd.toFixed(2)}` : e.llmCalls > 0 ? `about ${Math.round(e.estimatedTokens / 1000)}k tokens, $0 locally` : '$0'}</dd></div>
              </dl>
              <ul className="list-disc space-y-1 pl-5 text-xs text-muted">{e.notes.map((n) => <li key={n}>{n}</li>)}</ul>
            </>
          )}
        </section>

        {error && <p role="alert" className="text-sm text-red-500">{error}</p>}
        <div className="flex justify-end gap-2 pb-6">
          <Link to="/experiments" className="btn-ghost">Cancel</Link>
          <button className="btn-primary" disabled={!valid || create.isPending || (e != null && e.questions === 0)} onClick={() => create.mutate()}>
            {create.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <FlaskConical className="h-4 w-4" />} Run experiment
          </button>
        </div>
      </div>
    </div>
  )
}
