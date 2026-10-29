import { ChevronsUpDown, FlaskConical, Files, Inbox, ListChecks, LogOut, Moon, Plus, Scale, ScanSearch, Search, Sun, Wand2, type LucideIcon } from 'lucide-react'
import { motion } from 'motion/react'
import { Link, NavLink, useNavigate, useParams } from 'react-router-dom'
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

/** The navigation, in three groups: what you do with documents, how you measure the answers, and how you keep it running. */
const GROUPS: { label: string; items: { to: string; label: string; icon: LucideIcon; end?: boolean }[] }[] = [
  { label: 'Work', items: [{ to: '/documents', label: 'Library', icon: Files }] },
  {
    label: 'Measure',
    items: [
      { to: '/testsets', label: 'Test sets', icon: ListChecks },
      { to: '/experiments', label: 'Experiments', icon: FlaskConical },
      { to: '/autotune', label: 'AutoTune', icon: Wand2 },
      { to: '/calibration', label: 'Calibration', icon: Scale },
    ],
  },
  {
    label: 'Operate',
    items: [
      { to: '/feedback', label: 'Feedback', icon: Inbox },
      { to: '/xray', label: 'X-Ray', icon: ScanSearch, end: true },
    ],
  },
]

/** One navigation link. The highlight is a single shared element, so it glides from the old page to the new one instead of appearing. */
function NavItem({ to, label, icon: Icon, end, onClick }: { to: string; label: string; icon: LucideIcon; end?: boolean; onClick: () => void }) {
  return (
    <NavLink to={to} end={end} onClick={onClick} className="group relative flex items-center gap-3 rounded-xl px-3 py-2 text-[0.86rem] font-medium">
      {({ isActive }) => (
        <>
          {isActive && (
            <motion.span
              layoutId="nav-pill"
              className="absolute inset-0 rounded-xl border border-[var(--line-strong)]"
              style={{ background: 'linear-gradient(135deg, rgb(var(--accent-rgb) / 0.22), rgb(var(--accent2-rgb) / 0.07))' }}
              transition={{ type: 'spring', stiffness: 420, damping: 34 }}
            />
          )}
          {isActive && <span aria-hidden className="absolute bottom-2 left-0 top-2 w-[3px] rounded-full" style={{ background: 'var(--prism)' }} />}
          <Icon className={`relative h-[17px] w-[17px] transition-colors ${isActive ? 'text-accent' : 'text-muted group-hover:text-fg'}`} />
          <span className={`relative transition-colors ${isActive ? 'text-fg' : 'text-muted group-hover:text-fg'}`}>{label}</span>
        </>
      )}
    </NavLink>
  )
}

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
      {open && <button type="button" aria-label="Close menu" className="fixed inset-0 z-30 bg-black/55 backdrop-blur-sm lg:hidden" onClick={onClose} tabIndex={-1} />}
      <aside
        aria-label="Sidebar"
        className={`fixed inset-y-0 left-0 z-40 flex w-[17.5rem] flex-col border-r border-[var(--line)] bg-[var(--sidebar)] p-3.5 backdrop-blur-2xl transition-transform duration-300 lg:static lg:translate-x-0 ${open ? 'translate-x-0' : '-translate-x-full'}`}
      >
        <Link to="/chat" onClick={onClose} className="block rounded-xl px-2 pb-5 pt-2" aria-label="Glassbox home"><Wordmark /></Link>

        <button className="btn-primary w-full" onClick={newChat}>
          <Plus className="h-[17px] w-[17px]" strokeWidth={2.4} /> New chat
        </button>

        <button
          type="button"
          onClick={onOpenPalette}
          className="btn-glass mt-2.5 w-full !justify-start !px-3 !py-2 !text-[0.84rem] !font-medium text-muted"
          aria-label="Search and commands"
        >
          <Search className="h-4 w-4" /> Search
          <span className="kbd ml-auto">Ctrl K</span>
        </button>

        <nav aria-label="Main" className="mt-5 space-y-4">
          {GROUPS.map((g) => (
            <div key={g.label}>
              <h2 className="eyebrow px-3 pb-1.5 !text-muted/80">{g.label}</h2>
              <div className="space-y-0.5">
                {g.items.map((item) => <NavItem key={item.to} {...item} onClick={onClose} />)}
              </div>
            </div>
          ))}
        </nav>

        <div className="mt-5 min-h-0 flex-1 space-y-4 overflow-y-auto pr-1" aria-label="Recent chats">
          {history.isLoading && (
            <div className="space-y-2 px-1" aria-label="Loading history">
              {[0, 1, 2].map((i) => <div key={i} className="skeleton h-7" />)}
            </div>
          )}
          {groups.map((g) => (
            <section key={g.label}>
              <h2 className="eyebrow px-3 pb-1.5 !text-muted/80">{g.label}</h2>
              <ul className="space-y-0.5">
                {g.items.map((t) => (
                  <li key={t.id}>
                    <NavLink
                      to={`/chat/${t.id}`}
                      onClick={onClose}
                      title={t.question}
                      className={`block truncate rounded-lg px-3 py-1.5 text-[0.82rem] transition-colors ${traceId === t.id ? 'bg-accent/15 font-medium text-fg' : 'text-muted hover:bg-[var(--hover)] hover:text-fg'}`}
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
            <span aria-hidden className="grid h-8 w-8 shrink-0 place-items-center rounded-full text-[0.8rem] font-bold text-[var(--on-prism)]" style={{ background: 'var(--prism)' }}>
              {(user?.email ?? '?').slice(0, 1).toUpperCase()}
            </span>
            <span className="min-w-0 flex-1 truncate text-xs text-muted" title={user?.email}>{user?.email}</span>
            <button className="btn-ghost !p-2" onClick={onToggleTheme} aria-label={theme === 'dark' ? 'Switch to the light theme' : 'Switch to the dark theme'} title={theme === 'dark' ? 'Light theme' : 'Dark theme'}>
              {theme === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
            </button>
            <button className="btn-ghost !p-2" onClick={() => void signOut()} aria-label="Sign out" title="Sign out"><LogOut className="h-4 w-4" /></button>
          </div>
        </div>
      </aside>
    </>
  )
}
