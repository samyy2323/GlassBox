import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Eye, Menu, UploadCloud } from 'lucide-react'
import { AnimatePresence, motion } from 'motion/react'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useRecentTraces } from '../lib/history'
import { SUPPORTED_SUMMARY } from '../lib/formats'
import { useTheme } from '../lib/theme'
import { useUploads } from '../lib/uploads'
import { useWorkspace } from '../lib/workspace'
import { Aurora } from './Aurora'
import { CommandPalette, paletteIcons, type Command } from './CommandPalette'
import { Wordmark } from './Logo'
import { Sidebar } from './Sidebar'
import { UploadToast } from './UploadToast'

/** True while the user drags files (not text or links) over the window. */
function useFileDrag(onDrop: (files: File[]) => void) {
  const [active, setActive] = useState(false)
  const depth = useRef(0)
  const handler = useRef(onDrop)
  handler.current = onDrop

  useEffect(() => {
    const hasFiles = (e: DragEvent) => !!e.dataTransfer && [...e.dataTransfer.types].includes('Files')
    const enter = (e: DragEvent) => { if (hasFiles(e)) { depth.current++; setActive(true) } }
    const leave = (e: DragEvent) => { if (hasFiles(e) && --depth.current <= 0) { depth.current = 0; setActive(false) } }
    const over = (e: DragEvent) => { if (hasFiles(e)) e.preventDefault() }
    const drop = (e: DragEvent) => {
      if (!hasFiles(e)) return
      e.preventDefault()
      depth.current = 0
      setActive(false)
      if (e.dataTransfer?.files.length) handler.current([...e.dataTransfer.files])
    }
    window.addEventListener('dragenter', enter)
    window.addEventListener('dragleave', leave)
    window.addEventListener('dragover', over)
    window.addEventListener('drop', drop)
    return () => {
      window.removeEventListener('dragenter', enter)
      window.removeEventListener('dragleave', leave)
      window.removeEventListener('dragover', over)
      window.removeEventListener('drop', drop)
    }
  }, [])
  return active
}

export function Shell() {
  const navigate = useNavigate()
  const location = useLocation()
  const { user, signOut } = useAuth()
  const demo = useQuery({ queryKey: ['demo-info'], queryFn: api.demoInfo, retry: false, staleTime: Infinity })
  const isDemo = !!demo.data?.enabled && user?.email === demo.data.email
  const { workspaces, current, select } = useWorkspace()
  const { enqueue } = useUploads()
  const history = useRecentTraces()
  const [theme, toggleTheme] = useTheme()
  const [menu, setMenu] = useState(false)
  const [palette, setPalette] = useState(false)
  const picker = useRef<HTMLInputElement>(null)

  const dragging = useFileDrag(enqueue)

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setPalette((p) => !p)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const closeMenu = useCallback(() => setMenu(false), [])
  const closePalette = useCallback(() => setPalette(false), [])

  const commands = useMemo<Command[]>(() => {
    const list: Command[] = [
      { id: 'new', label: 'New chat', icon: paletteIcons.newChat, run: () => navigate('/chat', { state: { fresh: Date.now() } }) },
      { id: 'library', label: 'Open the library', hint: 'Documents', icon: paletteIcons.library, run: () => navigate('/documents') },
      { id: 'testsets', label: 'Open test sets', hint: 'Questions to measure answers', icon: paletteIcons.testsets, run: () => navigate('/testsets') },
      { id: 'experiments', label: 'Open experiments', hint: 'Measure configurations', icon: paletteIcons.experiments, run: () => navigate('/experiments') },
      { id: 'feedback', label: 'Open feedback', hint: 'Turn bad answers into test questions', icon: paletteIcons.feedback, run: () => navigate('/feedback') },
      { id: 'autotune', label: 'Open AutoTune', hint: 'Search for a better configuration', icon: paletteIcons.autotune, run: () => navigate('/autotune') },
      { id: 'calibration', label: 'Calibrate the judge', hint: 'Check its grades against yours', icon: paletteIcons.calibration, run: () => navigate('/calibration') },
      { id: 'xray', label: 'Open X-Ray', hint: 'How answers were made', icon: paletteIcons.xray, run: () => navigate('/xray') },
      { id: 'upload', label: 'Upload documents…', icon: paletteIcons.upload, run: () => picker.current?.click() },
      { id: 'theme', label: theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme', icon: theme === 'dark' ? paletteIcons.sun : paletteIcons.moon, run: toggleTheme },
    ]
    if (workspaces.length > 1) {
      for (const w of workspaces) if (w.id !== current?.id) list.push({ id: `ws-${w.id}`, label: `Switch to ${w.name}`, hint: 'Workspace', icon: paletteIcons.library, run: () => select(w.id) })
    }
    for (const t of (history.data?.items ?? []).slice(0, 8)) {
      list.push({ id: `chat-${t.id}`, label: t.question, hint: 'Recent chat', icon: paletteIcons.chat, run: () => navigate(`/chat/${t.id}`) })
    }
    list.push({ id: 'out', label: 'Sign out', icon: paletteIcons.signOut, run: () => void signOut() })
    return list
  }, [navigate, theme, toggleTheme, workspaces, current?.id, select, history.data, signOut])

  return (
    <div className="flex h-dvh overflow-hidden">
      <Aurora />
      <Sidebar open={menu} onClose={closeMenu} onOpenPalette={() => setPalette(true)} theme={theme} onToggleTheme={toggleTheme} />
      <div className="relative flex min-w-0 flex-1 flex-col">
        <header className="flex items-center gap-3 border-b border-[var(--line)] bg-[var(--sidebar)] p-3 backdrop-blur-xl lg:hidden">
          <button className="btn-ghost !rounded-xl !p-2.5" onClick={() => setMenu(true)} aria-label="Open menu"><Menu className="h-5 w-5" /></button>
          <Wordmark />
        </header>
        {isDemo && (
          <div role="status" className="flex flex-wrap items-center justify-center gap-x-3 gap-y-1 border-b border-[var(--line)] bg-accent/10 px-4 py-2 text-center text-[0.8rem]">
            <Eye className="h-3.5 w-3.5 text-accent" aria-hidden />
            <span>You are in the read-only demo: everything here was recorded in advance.</span>
            <button type="button" className="font-semibold text-accent underline-offset-4 hover:underline" onClick={() => void signOut()}>Create an account to use your own documents</button>
          </div>
        )}
        <main className="relative flex min-h-0 flex-1 flex-col">
          {/* A short fade and rise between pages. Keyed by the path, so moving between pages animates and a re-render of one page does not. */}
          <AnimatePresence mode="wait" initial={false}>
            <motion.div
              key={location.pathname}
              className="flex min-h-0 flex-1 flex-col"
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -6 }}
              transition={{ duration: 0.22, ease: [0.22, 1, 0.36, 1] }}
            >
              {/* Remount pages on workspace change so nothing from one workspace leaks into another's view. */}
              <Outlet key={current?.id} />
            </motion.div>
          </AnimatePresence>
        </main>
      </div>

      <input ref={picker} type="file" multiple className="sr-only" tabIndex={-1} aria-hidden onChange={(e) => { if (e.target.files?.length) enqueue([...e.target.files]); e.target.value = '' }} />
      <UploadToast />
      <CommandPalette open={palette} onClose={closePalette} commands={commands} />

      {dragging && (
        <div className="pointer-events-none fixed inset-0 z-[70] grid animate-fade-in place-items-center bg-black/40 p-6 backdrop-blur-md" aria-hidden>
          <div className="glass-strong flex max-w-md flex-col items-center gap-3 rounded-3xl border-2 border-dashed !border-fg/40 px-10 py-12 text-center">
            <UploadCloud className="h-12 w-12 animate-bounce text-fg" />
            <p className="text-lg font-semibold">Drop to add to your library</p>
            <p className="text-sm text-muted">{SUPPORTED_SUMMARY}</p>
          </div>
        </div>
      )}
    </div>
  )
}
