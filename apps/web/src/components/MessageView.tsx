import { useState } from 'react'
import { AlertTriangle, Check, Circle, Clipboard, Lightbulb, Loader2, RotateCcw, ScanSearch, ShieldCheck, Upload } from 'lucide-react'
import { Link } from 'react-router-dom'
import type { Citation } from '../lib/api'
import { progressSteps, type Turn } from '../lib/chatState'
import { RichText } from '../lib/richText'
import { Logo } from './Logo'

export function UserMessage({ text }: { text: string }) {
  return (
    <div className="flex animate-fade-up justify-end">
      <p className="max-w-[85%] whitespace-pre-wrap rounded-[1.4rem] rounded-br-md bg-fg px-5 py-3 text-[0.95rem] leading-relaxed text-canvas shadow-[0_8px_30px_rgb(var(--fg-rgb)/0.18)]">
        {text}
      </p>
    </div>
  )
}

function CitationChip({ label, known, active, onClick }: { label: string; known: boolean; active: boolean; onClick: () => void }) {
  const n = label.slice(1)
  if (!known) {
    return (
      <span title="The model cited a source that was not provided" className="mx-0.5 inline-flex h-[1.15rem] min-w-[1.15rem] items-center justify-center rounded-full border border-dashed border-fg/40 px-1 align-[0.12em] text-[10.5px] font-bold text-muted">
        {n}?
      </span>
    )
  }
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={`Open source ${n}`}
      aria-pressed={active}
      className={`mx-0.5 inline-flex h-[1.15rem] min-w-[1.15rem] items-center justify-center rounded-full border px-1 align-[0.12em] text-[10.5px] font-bold transition ${
        active ? 'border-transparent bg-fg text-canvas' : 'border-fg/25 bg-fg/[0.07] text-fg hover:bg-fg hover:text-canvas'
      }`}
    >
      {n}
    </button>
  )
}

function Steps({ turn }: { turn: Turn }) {
  const steps = progressSteps(turn)
  return (
    <ol className="space-y-2" aria-label="Progress">
      {steps.map((s) => (
        <li key={s.key} className={`flex items-center gap-2.5 text-[0.84rem] transition-opacity ${s.state === 'pending' ? 'opacity-35' : ''}`}>
          {s.state === 'done' ? <Check className="h-4 w-4 text-fg" strokeWidth={2.6} /> : s.state === 'active' ? <Loader2 className="h-4 w-4 animate-spin text-fg" /> : <Circle className="h-4 w-4 text-muted" />}
          <span className={s.state === 'active' ? 'font-medium' : 'text-muted'}>{s.label}</span>
        </li>
      ))}
    </ol>
  )
}

function SourceCards({ citations, active, onOpen }: { citations: Citation[]; active?: string; onOpen: (c: Citation) => void }) {
  if (citations.length === 0) return null
  return (
    <div className="space-y-2.5">
      <p className="eyebrow">Sources</p>
      <ul className="grid gap-2.5 sm:grid-cols-2">
        {citations.map((c, i) => (
          <li key={c.label} className="animate-fade-up" style={{ animationDelay: `${i * 60}ms` }}>
            <button
              type="button"
              onClick={() => onOpen(c)}
              className={`glass glass-interactive flex h-full w-full flex-col gap-1.5 rounded-2xl p-3.5 text-left ${active === c.label ? '!border-fg/60' : ''}`}
            >
              <span className="flex items-center gap-2.5">
                <span className="grid h-5 w-5 shrink-0 place-items-center rounded-full bg-fg text-[10.5px] font-bold text-canvas">{c.label.slice(1)}</span>
                <span className="truncate text-[0.84rem] font-semibold">{c.title}</span>
                {c.page != null && <span className="ml-auto shrink-0 text-[11px] font-medium text-muted">p.{c.page}</span>}
              </span>
              <span className="line-clamp-2 text-xs leading-relaxed text-muted">{c.text.replace(/\s+/g, ' ')}</span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

interface AssistantProps {
  turn: Turn
  isLast: boolean
  activeLabel?: string
  onOpenSource: (c: Citation) => void
  onRegenerate: () => void
  onUpload: () => void
}

export function AssistantMessage({ turn, isLast, activeLabel, onOpenSource, onRegenerate, onUpload }: AssistantProps) {
  const [copied, setCopied] = useState(false)
  const streaming = turn.status === 'streaming'
  const known = new Set(turn.citations.map((c) => c.label))
  const cited = turn.citations

  function copy() {
    void navigator.clipboard?.writeText(turn.answer).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 1600)
    })
  }

  return (
    <article className="flex animate-fade-up gap-3.5" aria-label="Assistant answer">
      <div className="pt-0.5"><Logo size={28} pulse={streaming && turn.answer === ''} /></div>
      <div className="min-w-0 flex-1 space-y-4" aria-live={streaming ? 'polite' : 'off'} aria-busy={streaming}>
        {streaming && turn.answer === '' && (
          <div className="glass w-fit max-w-full space-y-3.5 rounded-2xl px-5 py-4">
            <Steps turn={turn} />
            <div className="space-y-2 pt-1" aria-hidden>
              <div className="skeleton h-2.5 w-72 max-w-full" />
              <div className="skeleton h-2.5 w-52 max-w-full" />
            </div>
          </div>
        )}

        {turn.answer !== '' && (
          <div>
            <RichText
              text={turn.answer}
              renderCite={(label) => <CitationChip label={label} known={known.has(label)} active={activeLabel === label} onClick={() => { const c = cited.find((x) => x.label === label); if (c) onOpenSource(c) }} />}
            />
            {streaming && <span className="ml-0.5 inline-block h-[1.05em] w-[2.5px] translate-y-[3px] animate-caret rounded-full bg-fg" aria-hidden />}
          </div>
        )}

        {turn.abstained && (
          <div className="glass flex gap-3.5 rounded-2xl p-4 text-sm">
            <Lightbulb className="mt-0.5 h-[18px] w-[18px] shrink-0 text-muted" />
            <div className="space-y-1.5">
              <p className="font-semibold">Nothing in your documents answers this.</p>
              <p className="text-muted">Try asking it another way, or add the document that covers it.</p>
              <button className="btn-ghost -ml-2.5 !mt-2" onClick={onUpload}><Upload className="h-4 w-4" /> Upload documents</button>
            </div>
          </div>
        )}

        {turn.status === 'stopped' && <p className="text-sm text-muted">Stopped.</p>}
        {turn.status === 'error' && (
          <div role="alert" className="flex items-start gap-3 rounded-2xl border border-red-500/40 bg-red-500/10 p-4 text-sm">
            <AlertTriangle className="mt-0.5 h-[18px] w-[18px] shrink-0 text-red-500" />
            <div className="space-y-2">
              <p className="font-medium text-red-700 dark:text-red-300">{turn.error}</p>
              {isLast && <button className="btn-ghost -ml-2.5" onClick={onRegenerate}><RotateCcw className="h-4 w-4" /> Try again</button>}
            </div>
          </div>
        )}

        {turn.sanitized && (
          <p className="flex items-center gap-1.5 text-xs text-muted"><ShieldCheck className="h-3.5 w-3.5" /> Links and images were removed from this answer for your safety.</p>
        )}
        {turn.unknownLabels.length > 0 && (
          <p className="text-xs text-muted">The model cited {turn.unknownLabels.join(', ')}, which were not among the sources. Treat that part with caution.</p>
        )}

        {!streaming && <SourceCards citations={cited} active={activeLabel} onOpen={onOpenSource} />}

        {!streaming && (
          <div className="-ml-2 flex flex-wrap items-center gap-0.5 text-xs text-muted">
            {turn.answer !== '' && (
              <button className="btn-ghost !px-2 !py-1" onClick={copy}>
                {copied ? <Check className="h-3.5 w-3.5" /> : <Clipboard className="h-3.5 w-3.5" />} {copied ? 'Copied' : 'Copy'}
              </button>
            )}
            {isLast && turn.status !== 'error' && (
              <button className="btn-ghost !px-2 !py-1" onClick={onRegenerate}><RotateCcw className="h-3.5 w-3.5" /> Regenerate</button>
            )}
            {turn.traceId && (
              <Link to={`/xray/${turn.traceId}`} className="btn-ghost !px-2 !py-1"><ScanSearch className="h-3.5 w-3.5" /> How this was made</Link>
            )}
            {turn.model && <span className="ml-auto pl-2 pr-1 tabular-nums">{turn.model}{turn.latencyMs != null ? ` · ${(turn.latencyMs / 1000).toFixed(1)} s` : ''}</span>}
          </div>
        )}
      </div>
    </article>
  )
}
