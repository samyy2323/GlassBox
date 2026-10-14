import { useState, type FormEvent } from 'react'
import { ArrowRight, Loader2, Moon, ScanSearch, ShieldCheck, Sun, Zap } from 'lucide-react'
import { Navigate } from 'react-router-dom'
import { Aurora } from '../components/Aurora'
import { Wordmark } from '../components/Logo'
import { useAuth } from '../lib/auth'
import { useTheme } from '../lib/theme'

const FEATURES = [
  { icon: Zap, title: 'Answers you can check', text: 'Every answer cites the exact passage it came from.' },
  { icon: ScanSearch, title: 'See how it was made', text: 'X-Ray shows what was retrieved, ranked and dropped.' },
  { icon: ShieldCheck, title: 'Private by design', text: 'Your workspace is isolated by the database itself.' },
]

export default function Login() {
  const { user, signIn } = useAuth()
  const [theme, toggleTheme] = useTheme()
  const [register, setRegister] = useState(false)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  if (user) return <Navigate to="/chat" replace />

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
    <div className="relative grid min-h-dvh place-items-center overflow-hidden px-4 py-10">
      <Aurora />
      <button className="btn-ghost glass absolute right-4 top-4 !rounded-full !p-2.5" onClick={toggleTheme} aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}>
        {theme === 'dark' ? <Sun className="h-[18px] w-[18px]" /> : <Moon className="h-[18px] w-[18px]" />}
      </button>

      <main className="grid w-full max-w-5xl items-center gap-10 lg:grid-cols-[1.15fr_1fr]">
        <section className="animate-fade-up space-y-8">
          <Wordmark />
          <div className="space-y-4">
            <h1 className="text-4xl font-bold leading-[1.1] tracking-tight sm:text-5xl">
              Ask your documents.<br />
              <span className="text-gradient">Trust the answer.</span>
            </h1>
            <p className="max-w-md text-base text-muted">Upload anything, ask in plain language, and get answers with sources you can open and verify.</p>
          </div>
          <ul className="hidden space-y-4 lg:block">
            {FEATURES.map((f, i) => (
              <li key={f.title} className="flex animate-fade-up items-start gap-4" style={{ animationDelay: `${150 + i * 90}ms` }}>
                <span className="glass grid h-10 w-10 shrink-0 place-items-center rounded-xl text-fg"><f.icon className="h-5 w-5" /></span>
                <span>
                  <span className="block text-sm font-semibold">{f.title}</span>
                  <span className="block text-sm text-muted">{f.text}</span>
                </span>
              </li>
            ))}
          </ul>
        </section>

        <form onSubmit={submit} className="glass-strong animate-pop-in space-y-5 rounded-[2rem] p-7 sm:p-9">
          <div>
            <h2 className="text-xl font-bold">{register ? 'Create your account' : 'Welcome back'}</h2>
            <p className="mt-1 text-sm text-muted">{register ? 'You get a private workspace right away.' : 'Sign in to your workspace.'}</p>
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
          {error && <p role="alert" className="rounded-xl border border-red-400/40 bg-red-500/10 px-3 py-2 text-sm text-red-700 dark:text-red-300">{error}</p>}
          <button className="btn-primary w-full !py-3" disabled={busy}>
            {busy ? <Loader2 className="h-5 w-5 animate-spin" /> : <>{register ? 'Create account' : 'Sign in'} <ArrowRight className="h-4 w-4" /></>}
          </button>
          <p className="text-center text-sm text-muted">
            {register ? 'Already have an account?' : 'New here?'}{' '}
            <button type="button" className="font-semibold text-fg underline-offset-4 hover:underline" onClick={() => { setRegister(!register); setError(null) }}>
              {register ? 'Sign in' : 'Create an account'}
            </button>
          </p>
        </form>
      </main>
    </div>
  )
}
