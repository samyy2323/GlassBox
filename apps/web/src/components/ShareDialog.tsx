import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Clipboard, FileDown, Link2, Loader2, Trash2 } from 'lucide-react'
import { api, ApiError } from '../lib/api'
import { expiryText, shareUrl } from '../lib/report'
import { queryKeys } from '../lib/queryKeys'
import { Dialog } from './Dialog'

/** Turn a finished run into a report: a PDF to download, or a read-only link that expires and can be revoked. The snapshot is frozen when the report is made. */
export function ShareDialog({ kind, refId, onClose }: { kind: 'EXPERIMENT' | 'AUTOTUNE'; refId: string; onClose: () => void }) {
  const qc = useQueryClient()
  const [redact, setRedact] = useState(false)
  const [days, setDays] = useState(7)
  const [token, setToken] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const create = useMutation({ mutationFn: () => api.createReport(kind, refId, redact) })
  const reportId = create.data?.id
  const links = useQuery({ queryKey: queryKeys.reportLinks(reportId ?? ''), queryFn: () => api.reportLinks(reportId!), enabled: !!reportId })
  const refresh = () => qc.invalidateQueries({ queryKey: queryKeys.reportLinks(reportId ?? '') })
  const link = useMutation({ mutationFn: () => api.createReportLink(reportId!, days), onSuccess: (c) => { setToken(c.token); refresh() } })
  const revoke = useMutation({ mutationFn: (id: string) => api.revokeReportLink(reportId!, id), onSuccess: () => { setToken(null); refresh() } })
  const pdf = useMutation({
    mutationFn: async () => {
      const blob = await api.reportPdf(reportId!)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = 'glassbox-report.pdf'
      a.click()
      URL.revokeObjectURL(url)
    },
  })
  const error = [create.error, link.error, revoke.error, pdf.error].map((e) => (e instanceof ApiError ? e.message : e ? 'That did not work.' : null)).find(Boolean)
  const url = token ? shareUrl(window.location.origin, token) : ''

  return (
    <Dialog title="Share a report" onClose={onClose}>
      <div className="space-y-4">
        {!reportId ? (
          <>
            <p className="text-sm text-muted">A report is a frozen copy of this run: its numbers and intervals, verdicts, configurations, the failure histogram, test set statistics and the limitations. It does not change if the run does.</p>
            <label className="flex items-start gap-2 text-sm"><input type="checkbox" className="mt-1 accent-[rgb(var(--accent-rgb))]" checked={redact} onChange={(e) => setRedact(e.target.checked)} />
              <span>Leave out the text of questions<span className="block text-xs text-muted">The report then holds numbers only, nothing that could identify a source.</span></span></label>
            <button className="btn-primary" onClick={() => create.mutate()} disabled={create.isPending}>{create.isPending && <Loader2 className="h-4 w-4 animate-spin" />} Make the report</button>
          </>
        ) : (
          <>
            <p className="text-sm">Report made{create.data?.redacted ? ' (question texts left out)' : ''}.</p>
            <button className="btn-ghost !border !border-[var(--line)]" onClick={() => pdf.mutate()} disabled={pdf.isPending}>{pdf.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <FileDown className="h-4 w-4" />} Download the PDF</button>

            <div className="space-y-2 rounded-xl border border-[var(--line)] p-3">
              <h3 className="text-sm font-semibold">Read-only link</h3>
              <p className="text-xs text-muted">Anyone with the link can read this report, without signing in, until it expires or you revoke it. The link opens this one report and nothing else. It is shown once.</p>
              <div className="flex flex-wrap items-center gap-2">
                <label className="text-xs text-muted">Valid for
                  <select className="input-glass ml-2 !w-auto !py-1 text-sm" value={days} onChange={(e) => setDays(Number(e.target.value))}>{[1, 7, 30, 90].map((d) => <option key={d} value={d}>{d} day{d === 1 ? '' : 's'}</option>)}</select>
                </label>
                <button className="btn-primary" onClick={() => link.mutate()} disabled={link.isPending}>{link.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Link2 className="h-4 w-4" />} Create a link</button>
              </div>
              {url && (
                <div className="flex items-center gap-2">
                  <input readOnly className="input-glass !py-1.5 text-xs" value={url} aria-label="The link" onFocus={(e) => e.currentTarget.select()} />
                  <button className="btn-ghost shrink-0" onClick={() => { navigator.clipboard?.writeText(url); setCopied(true) }}>{copied ? <Check className="h-4 w-4" /> : <Clipboard className="h-4 w-4" />}</button>
                </div>
              )}
              <ul className="space-y-1 text-xs">
                {(links.data ?? []).map((l) => (
                  <li key={l.id} className="flex items-center gap-2"><span className={l.active ? '' : 'text-muted line-through'}>Link made {new Date(l.createdAt).toLocaleDateString()}</span><span className="text-muted">{l.revokedAt ? 'revoked' : expiryText(l.expiresAt)}</span>
                    {l.active && <button className="btn-ghost ml-auto !p-1" aria-label="Revoke this link" onClick={() => revoke.mutate(l.id)}><Trash2 className="h-3.5 w-3.5 text-red-500" /></button>}</li>
                ))}
              </ul>
            </div>
          </>
        )}
        {error && <p role="alert" className="text-sm text-red-500">{error}</p>}
      </div>
    </Dialog>
  )
}
