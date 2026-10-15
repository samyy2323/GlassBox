import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { CornerDownLeft, Files, FlaskConical, ListChecks, LogOut, MessageSquare, MessageSquarePlus, Moon, Scale, ScanSearch, Search, Sun, Upload, Wallet, Wand2 } from 'lucide-react'

export interface Command {
  id: string
  label: string
  hint?: string
  icon: ReactNode
  run: () => void
}

interface Props {
  open: boolean
  onClose: () => void
  commands: Command[]
}

/** Ctrl+K: type to filter, arrows to move, Enter to run, Esc to close. */
export function CommandPalette({ open, onClose, commands }: Props) {
  const [query, setQuery] = useState('')
  const [index, setIndex] = useState(0)
  const input = useRef<HTMLInputElement>(null)

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    return q ? commands.filter((c) => c.label.toLowerCase().includes(q) || c.hint?.toLowerCase().includes(q)) : commands
  }, [commands, query])

  useEffect(() => {
    if (open) {
      setQuery('')
      setIndex(0)
      setTimeout(() => input.current?.focus(), 0)
    }
  }, [open])

  useEffect(() => setIndex(0), [query])

  if (!open) return null

  function run(c: Command | undefined) {
    if (!c) return
    onClose()
    c.run()
  }

  return (
    <div className="fixed inset-0 z-[60] flex items-start justify-center px-4 pt-[14vh]" role="dialog" aria-modal="true" aria-label="Command palette">
      <button type="button" aria-label="Close" className="absolute inset-0 animate-fade-in bg-black/45 backdrop-blur-sm" onClick={onClose} tabIndex={-1} />
      <div
        className="glass-strong relative w-full max-w-xl animate-pop-in overflow-hidden rounded-3xl"
        onKeyDown={(e) => {
          if (e.key === 'Escape') onClose()
          else if (e.key === 'ArrowDown') { e.preventDefault(); setIndex((i) => Math.min(shown.length - 1, i + 1)) }
          else if (e.key === 'ArrowUp') { e.preventDefault(); setIndex((i) => Math.max(0, i - 1)) }
          else if (e.key === 'Enter') { e.preventDefault(); run(shown[index]) }
        }}
      >
        <div className="flex items-center gap-3 border-b border-[var(--line)] px-5 py-4">
          <Search className="h-5 w-5 text-muted" />
          <input
            ref={input}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search chats, jump to a page, run a command…"
            aria-label="Search commands"
            className="flex-1 bg-transparent text-base outline-none placeholder:text-muted"
          />
          <kbd className="rounded-md border border-[var(--line)] px-1.5 py-0.5 text-[11px] text-muted">Esc</kbd>
        </div>
        <ul className="max-h-[50vh] overflow-y-auto p-2" role="listbox">
          {shown.map((c, i) => (
            <li key={c.id} role="option" aria-selected={i === index}>
              <button
                type="button"
                onMouseEnter={() => setIndex(i)}
                onClick={() => run(c)}
                className={`flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm transition ${i === index ? 'bg-[var(--glass-strong)]' : ''}`}
              >
                <span className="text-muted">{c.icon}</span>
                <span className="min-w-0 flex-1 truncate font-medium">{c.label}</span>
                {c.hint && <span className="shrink-0 text-xs text-muted">{c.hint}</span>}
                {i === index && <CornerDownLeft className="h-3.5 w-3.5 text-muted" />}
              </button>
            </li>
          ))}
          {shown.length === 0 && <li className="px-4 py-8 text-center text-sm text-muted">Nothing matches “{query}”.</li>}
        </ul>
      </div>
    </div>
  )
}

// eslint-disable-next-line react-refresh/only-export-components
export const paletteIcons = {
  newChat: <MessageSquarePlus className="h-4 w-4" />,
  library: <Files className="h-4 w-4" />,
  xray: <ScanSearch className="h-4 w-4" />,
  testsets: <ListChecks className="h-4 w-4" />,
  experiments: <FlaskConical className="h-4 w-4" />,
  autotune: <Wand2 className="h-4 w-4" />,
  budget: <Wallet className="h-4 w-4" />,
  calibration: <Scale className="h-4 w-4" />,
  upload: <Upload className="h-4 w-4" />,
  chat: <MessageSquare className="h-4 w-4" />,
  sun: <Sun className="h-4 w-4" />,
  moon: <Moon className="h-4 w-4" />,
  signOut: <LogOut className="h-4 w-4" />,
}
