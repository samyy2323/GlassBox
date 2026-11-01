import { useQuery } from '@tanstack/react-query'
import { FileDown } from 'lucide-react'
import { useParams } from 'react-router-dom'
import { ReportView, type ReportSnapshot } from '../components/ReportView'
import { expiryText } from '../lib/report'

interface Shared {
  title: string
  snapshot: ReportSnapshot
  createdAt: string
  expiresAt: string
}

/** A report opened from a share link. No sign-in: the link is the credential, and it reads one frozen snapshot. Nothing here calls an authenticated endpoint. */
export default function PublicReport() {
  const { token = '' } = useParams()
  const report = useQuery({
    queryKey: ['public-report', token],
    retry: false,
    queryFn: async (): Promise<Shared> => {
      const res = await fetch(`/api/public/reports/${encodeURIComponent(token)}`, { credentials: 'omit' })
      if (!res.ok) throw new Error('invalid')
      return res.json()
    },
  })
  return (
    <div className="min-h-dvh overflow-y-auto bg-bg text-fg">
      <div className="mx-auto w-full max-w-3xl space-y-5 p-4 sm:p-8">
        {report.isLoading && <div className="skeleton h-40" aria-label="Loading the report" />}
        {report.isError && (
          <div className="card" role="alert">
            <h1 className="text-[1.6rem] leading-tight">This link is not valid</h1>
            <p className="mt-1 text-sm text-muted">It may have expired or been revoked, or it may have been copied incorrectly. Ask whoever sent it for a new one.</p>
          </div>
        )}
        {report.data && (
          <>
            <div className="flex flex-wrap items-center justify-between gap-3 text-xs text-muted">
              <span>Read-only report · {expiryText(report.data.expiresAt)}</span>
              <a className="btn-ghost" href={`/api/public/reports/${encodeURIComponent(token)}/pdf`}><FileDown className="h-4 w-4" /> Download the PDF</a>
            </div>
            <ReportView snapshot={report.data.snapshot} />
          </>
        )}
      </div>
    </div>
  )
}
