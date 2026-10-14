import { ChevronsUpDown, Files, LogOut, Moon, Plus, ScanSearch, Search, Sun } from 'lucide-react'
import { NavLink, useNavigate, useParams } from 'react-router-dom'
import { useAuth } from '../lib/auth'
import { groupByDay, useRecentTraces } from '../lib/history'
import type { Theme } from '../lib/theme'
import { useWorkspace } from '../lib/workspace'
import { Wordmark } from './Logo'

interface Props {
  open: boolean
  onClose: () => void
  onOpenPalette: () => void
  theme: Theme
  onToggleTheme: () => void
}

const navLink = ({ isActive }: { isActive: boolean }) =>
  `group relative flex items-center gap-3 rounded-xl px-3 py-2 text-[0.84rem] font-medium transition-colors ${
    isActive ? 'bg-fg/[0.08] text-fg' : 'text-muted hover:bg-fg/[0.05] hover:text-fg'
  }`

export function Sidebar({ open, onClose, onOpenPalette, theme, onToggleTheme }: Props) {
  const { user, signOut } = useAuth()
  const { workspaces, current, select } = useWorkspace()
  const navigate = useNavigate()
  const { traceId } = useParams()
  const history = useRecentTraces()
  const groups = groupByDay(history.data?.items ?? [])

  function newChat() {
    navigate('/chat', { state: { fresh: Date.now() } })
    onClose()
  }

  return (
    <>
      {open && <button type="button" aria-label="Close menu" className="fixed inset-0 z-30 bg-black/50 backdrop-blur-sm lg:hidden" onClick={onClose} tabIndex={-1} />}
      <aside
        aria-label="Sidebar"
        className={`glass-strong fixed inset-y-0 left-0 z-40 flex w-[17.5rem] flex-col rounded-r-[1.75rem] p-3 transition-transform duration-300 lg:static lg:m-3 lg:mr-0 lg:translate-x-0 lg:rounded-[1.75rem] ${open ? 'translate-x-0' : '-translate-x-full'}`}
      >
        <div className="px-2 pb-4 pt-2"><Wordmark /></div>

        <button className="btn-primary w-full" onClick={newChat}>
          <Plus className="h-[17px] w-[17px]" strokeWidth={2.4} /> New chat
        </button>

        <button
          type="button"
          onClick={onOpenPalette}
          className="mt-2.5 flex w-full items-center gap-2.5 rounded-xl border border-[var(--line)] bg-fg/[0.03] px-3 py-2 text-[0.84rem] text-muted transition hover:bg-fg/[0.07] hover:text-fg"
          aria-label="Search and commands"
        >
          <Search className="h-4 w-4" /> Search
          <span className="kbd ml-auto">Ctrl K</span>
        </button>

        <nav aria-label="Main" className="mt-4 space-y-0.5">
          <NavLink to="/documents" className={navLink} onClick={onClose}><Files className="h-[17px] w-[17px]" /> Library</NavLink>
          <NavLink to="/xray" end className={navLink} onClick={onClose}><ScanSearch className="h-[17px] w-[17px]" /> X-Ray</NavLink>
        </nav>

        <div className="mt-5 min-h-0 flex-1 space-y-4 overflow-y-auto pr-1" aria-label="Recent chats">
          {history.isLoading && (
            <div className="space-y-2 px-1" aria-label="Loading history">
              {[0, 1, 2].map((i) => <div key={i} className="skeleton h-7" />)}
            </div>
          )}
          {groups.map((g) => (
            <section key={g.label}>
              <h2 className="eyebrow px-3 pb-1.5">{g.label}</h2>
              <ul className="space-y-0.5">
                {g.items.map((t) => (
                  <li key={t.id}>
                    <NavLink
                      to={`/chat/${t.id}`}
                      onClick={onClose}
                      title={t.question}
                      className={`block truncate rounded-lg px-3 py-1.5 text-[0.82rem] transition-colors ${traceId === t.id ? 'bg-fg/[0.08] font-medium text-fg' : 'text-muted hover:bg-fg/[0.05] hover:text-fg'}`}
                    >
                      {t.question}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </section>
          ))}
          {history.isSuccess && groups.length === 0 && <p className="px-3 text-[0.82rem] leading-relaxed text-muted">Your conversations will appear here.</p>}
        </div>

        <div className="mt-2 space-y-2.5 border-t border-[var(--line)] pt-3">
          <label className="relative block">
            <span className="sr-only">Workspace</span>
            <select className="input-glass cursor-pointer appearance-none !rounded-xl !py-2 pr-9 text-[0.84rem] font-medium" value={current?.id ?? ''} onChange={(e) => select(e.target.value)}>
              {workspaces.map((w) => <option key={w.id} value={w.id}>{w.name}</option>)}
            </select>
            <ChevronsUpDown className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted" />
          </label>
          <div className="flex items-center gap-2.5 px-1">
            <span aria-hidden className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-fg text-[0.8rem] font-bold text-canvas">
              {(user?.email ?? '?').slice(0, 1).toUpperCase()}
            </span>
            <span className="min-w-0 flex-1 truncate text-xs text-muted" title={user?.email}>{user?.email}</span>
            <button className="btn-ghost !p-2" onClick={onToggleTheme} aria-label={theme === 'dark' ? 'Switch to the white theme' : 'Switch to the black theme'} title={theme === 'dark' ? 'White theme' : 'Black theme'}>
              {theme === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
            </button>
            <button className="btn-ghost !p-2" onClick={() => void signOut()} aria-label="Sign out" title="Sign out"><LogOut className="h-4 w-4" /></button>
          </div>
        </div>
      </aside>
    </>
  )
}
