import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api, type TestQuestion, type TestSet } from '../lib/api'
import { queryKeys } from '../lib/queryKeys'
import { Dialog } from './Dialog'

function Changed({ label, before, after }: { label: string; before: string; after: string }) {
  if (before === after) return null
  return (
    <div className="mt-2 space-y-1 text-xs">
      <p className="eyebrow">{label}</p>
      <p className="rounded-lg bg-red-500/10 px-2 py-1 text-red-500 line-through decoration-red-500/40">{before}</p>
      <p className="rounded-lg bg-ok/10 px-2 py-1 text-ok">{after}</p>
    </div>
  )
}

function Section({ title, tone, children, count }: { title: string; tone: string; children: React.ReactNode; count: number }) {
  if (count === 0) return null
  return (
    <section className="space-y-2">
      <h3 className={`text-sm font-semibold ${tone}`}>{title} ({count})</h3>
      <ul className="space-y-2">{children}</ul>
    </section>
  )
}

const Q = ({ q }: { q: TestQuestion }) => (
  <li className="rounded-xl border border-[var(--line)] p-3 text-sm">
    <p className="font-medium">{q.question}</p>
    <p className="mt-1 text-xs text-muted">{q.expectedAnswer}</p>
  </li>
)

/** What changed between two versions of a test set: questions are matched by their stable id. */
export function DiffDialog({ testset, others, onClose }: { testset: TestSet; others: TestSet[]; onClose: () => void }) {
  const [against, setAgainst] = useState(others.find((o) => o.id === testset.parentVersionId)?.id ?? others[0]?.id ?? '')
  const diff = useQuery({ queryKey: queryKeys.testsetDiff(testset.id, against), queryFn: () => api.testsetDiff(testset.id, against), enabled: !!against })
  const d = diff.data

  return (
    <Dialog title={`Version ${testset.version} compared with…`} onClose={onClose} wide>
      <div className="space-y-5">
        <label className="flex flex-wrap items-center gap-3 text-sm font-medium">
          Compare with
          <select className="input-glass !w-auto" value={against} onChange={(e) => setAgainst(e.target.value)}>
            {others.map((o) => <option key={o.id} value={o.id}>Version {o.version} ({o.status.toLowerCase()})</option>)}
          </select>
        </label>
        {diff.isLoading && <div className="skeleton h-24" />}
        {diff.isError && <p role="alert" className="text-sm text-red-500">Could not compare these versions.</p>}
        {d && (
          <>
            <p className="text-sm text-muted">
              {d.added.length} added · {d.removed.length} removed · {d.edited.length} edited · {d.unchanged} unchanged. Rejected questions are not counted.
            </p>
            <Section title="Added" tone="text-ok" count={d.added.length}>{d.added.map((q) => <Q key={q.id} q={q} />)}</Section>
            <Section title="Removed" tone="text-red-500" count={d.removed.length}>{d.removed.map((q) => <Q key={q.id} q={q} />)}</Section>
            <Section title="Edited" tone="text-warn" count={d.edited.length}>
              {d.edited.map(({ before, after }) => (
                <li key={after.id} className="rounded-xl border border-[var(--line)] p-3 text-sm">
                  <p className="font-medium">{after.question}</p>
                  <Changed label="Question" before={before.question} after={after.question} />
                  <Changed label="Expected answer" before={before.expectedAnswer} after={after.expectedAnswer} />
                  <Changed label="Type" before={before.qtype} after={after.qtype} />
                  <Changed
                    label="Evidence"
                    before={before.evidence.map((e) => `${e.title} ${e.charStart}-${e.charEnd}`).join('; ')}
                    after={after.evidence.map((e) => `${e.title} ${e.charStart}-${e.charEnd}`).join('; ')}
                  />
                </li>
              ))}
            </Section>
            {d.added.length + d.removed.length + d.edited.length === 0 && <p className="text-sm text-muted">These versions have the same questions.</p>}
          </>
        )}
      </div>
    </Dialog>
  )
}
