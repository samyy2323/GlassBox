import { useState, type FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, Loader2, Moon, ScanSearch, ShieldCheck, Sun, Zap } from 'lucide-react'
import { Navigate } from 'react-router-dom'
import { Aurora } from '../components/Aurora'
import { CubeStage } from '../components/CubeStage'
import { Wordmark } from '../components/Logo'
import { Tabs } from '../components/Tabs'
import { api } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useTheme } from '../lib/theme'

// Three proof points, as small pills under the headline. Each is a promise the rest of the app keeps.
const PROOFS = [
  { icon: Zap, title: 'Answers you can check' },
  { icon: ScanSearch, title: 'See how it was made' },
  { icon: ShieldCheck, title: 'Private by design' },
]

export default function Login() {
  const { user, signIn, signInDemo } = useAuth()
  // The server says whether it runs a public read-only demo; a local install does not, and then there is no button.
  const demo = useQuery({ queryKey: ['demo-info'], queryFn: api.demoInfo, retry: false, staleTime: Infinity })
  const [theme, toggleTheme] = useTheme()
  const [mode, setMode] = useState<'in' | 'up'>('in')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const register = mode === 'up'

  if (user) return <Navigate to="/chat" replace />

  async function tryDemo() {
    setBusy(true)
    setError(null)
    try {
      await signInDemo()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'The demo is not available')
      setBusy(false)
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await signIn(email, password, register)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="relative h-dvh overflow-hidden">
      <Aurora />
      <button className="btn-glass absolute right-4 top-4 z-10 !rounded-full !p-2.5" onClick={toggleTheme} aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}>
        {theme === 'dark' ? <Sun className="h-[18px] w-[18px]" /> : <Moon className="h-[18px] w-[18px]" />}
      </button>

      {/* One screen, never a scroll: every size below is tied to the window height, and the parts that cannot fit a short window are the first to go. */}
      <main className="mx-auto grid h-full w-full max-w-[1280px] grid-cols-1 gap-4 px-5 py-4 lg:grid-cols-[1.1fr_0.9fr] lg:gap-14 lg:px-10 lg:py-7">
        <section className="hidden min-h-0 flex-col lg:flex">
          <Wordmark size={30} />

          <div className="relative min-h-0 flex-1">
            <CubeStage className="absolute inset-0" stillSize={170} waiting="mark" />
          </div>

          <div>
            <h1 className="text-[clamp(2.3rem,min(7.6vh,5vw),4.6rem)] leading-[1.0]">
              Ask your documents.<br />
              <span className="text-gradient font-light italic">Trust the answer.</span>
            </h1>
            <p className="mt-[clamp(0.6rem,2vh,1.25rem)] max-w-md text-[0.95rem] leading-relaxed text-muted [@media(max-height:700px)]:hidden">
              Upload anything, ask in plain language, and get answers with sources you can open and verify, then measure how good they really are.
            </p>
            <ul className="mt-[clamp(0.7rem,2.4vh,1.5rem)] flex flex-wrap gap-2 [@media(max-height:620px)]:hidden">
              {PROOFS.map((p) => (
                <li key={p.title} className="glass flex items-center gap-2 rounded-full py-1.5 pl-2 pr-3.5 text-[0.78rem] font-medium">
                  <span className="grid h-6 w-6 place-items-center rounded-full bg-accent/15 text-accent"><p.icon className="h-3.5 w-3.5" /></span>
                  {p.title}
                </li>
              ))}
            </ul>
          </div>
        </section>

        <section className="flex min-h-0 flex-col items-center justify-center lg:items-end">
          <div className="mb-4 lg:hidden"><Wordmark size={28} /></div>
          <form onSubmit={submit} className="glass-strong w-full max-w-md animate-rise space-y-[clamp(0.7rem,2.1vh,1.25rem)] rounded-[1.75rem] p-[clamp(1.25rem,3.4vh,2.25rem)]">
            <Tabs
              label="Sign in or create an account"
              value={mode}
              onChange={(m) => { setMode(m); setError(null) }}
              tabs={[{ id: 'in', label: 'Sign in' }, { id: 'up', label: 'Create account' }]}
            />
            <div>
              <h2 className="display text-[clamp(1.7rem,4.2vh,2.2rem)] font-light">{register ? 'Create your account' : 'Welcome back'}</h2>
              <p className="mt-1 text-sm text-muted [@media(max-height:620px)]:hidden">{register ? 'You get a private workspace right away.' : 'Sign in to your workspace.'}</p>
            </div>
            <label className="block space-y-1.5 text-sm font-medium">
              Email
              <input className="input-glass" type="email" required autoComplete="email" placeholder="you@company.com" value={email} onChange={(e) => setEmail(e.target.value)} />
            </label>
            <label className="block space-y-1.5 text-sm font-medium">
              Password
              <input
                className="input-glass"
                type="password"
                required
                minLength={register ? 8 : undefined}
                autoComplete={register ? 'new-password' : 'current-password'}
                placeholder={register ? 'At least 8 characters' : 'Your password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </label>
            {error && <p role="alert" className="rounded-xl border border-danger/40 bg-danger/10 px-3 py-2 text-sm text-danger">{error}</p>}
            <button className="btn-primary w-full !py-3" disabled={busy}>
              {busy ? <Loader2 className="h-5 w-5 animate-spin" /> : <>{register ? 'Create account' : 'Sign in'} <ArrowRight className="h-4 w-4" /></>}
            </button>
            {demo.data?.enabled && (
              <button type="button" className="btn-glass w-full !py-2.5" disabled={busy} onClick={() => void tryDemo()}>
                Try the demo <span className="text-xs font-normal text-muted">read-only, no sign-up</span>
              </button>
            )}
            <p className="flex items-center justify-center gap-2 text-center text-xs text-muted [@media(max-height:700px)]:hidden">
              <ShieldCheck className="h-3.5 w-3.5" aria-hidden /> Each workspace is isolated by the database itself.
            </p>
          </form>
        </section>
      </main>
    </div>
  )
}
