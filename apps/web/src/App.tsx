import { useEffect, useState } from 'react'
import { BrowserRouter, Navigate, Outlet, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { api } from './lib/api'
import { AuthProvider, useAuth } from './lib/auth'
import Documents from './pages/Documents'
import Login from './pages/Login'

const queryClient = new QueryClient()

function useDarkMode() {
  const [dark, setDark] = useState(() => {
    try {
      return localStorage.getItem('theme') === 'dark' || (!localStorage.getItem('theme') && matchMedia('(prefers-color-scheme: dark)').matches)
    } catch {
      return false
    }
  })
  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark)
    try {
      localStorage.setItem('theme', dark ? 'dark' : 'light')
    } catch {
      /* storage unavailable */
    }
  }, [dark])
  return [dark, () => setDark((d) => !d)] as const
}

function Layout() {
  const { user, loading, signOut } = useAuth()
  const [dark, toggle] = useDarkMode()
  const workspaces = useQuery({ queryKey: ['workspaces'], queryFn: api.workspaces, enabled: !!user })

  if (loading) return <p className="p-8 text-sm text-slate-500">Loading…</p>
  if (!user) return <Navigate to="/login" replace />

  return (
    <div className="min-h-screen">
      <header className="flex items-center gap-4 border-b border-slate-200 bg-white px-6 py-3 dark:border-slate-800 dark:bg-slate-900">
        <span className="font-semibold">Glassbox</span>
        <select aria-label="Workspace" className="input w-auto" defaultValue={workspaces.data?.[0]?.id}>
          {(workspaces.data ?? []).map((w) => (
            <option key={w.id} value={w.id}>
              {w.name}
            </option>
          ))}
        </select>
        <span className="ml-auto text-sm text-slate-500">{user.email}</span>
        <button className="text-sm hover:underline" onClick={toggle} aria-pressed={dark}>
          {dark ? 'Light' : 'Dark'} mode
        </button>
        <button className="text-sm hover:underline" onClick={signOut}>
          Sign out
        </button>
      </header>
      <main className="mx-auto max-w-5xl p-6">
        <Outlet />
      </main>
    </div>
  )
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/login" element={<Login />} />
            <Route element={<Layout />}>
              <Route path="/documents" element={<Documents />} />
              <Route path="*" element={<Navigate to="/documents" replace />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  )
}
