import { useEffect, useState } from 'react'
import { BrowserRouter, Navigate, NavLink, Outlet, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AuthProvider, useAuth } from './lib/auth'
import { WorkspaceProvider, useWorkspace } from './lib/workspace'
import Chat from './pages/Chat'
import Documents from './pages/Documents'
import Login from './pages/Login'

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } } })

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

const navClass = ({ isActive }: { isActive: boolean }) =>
  `rounded-md px-3 py-1.5 text-sm font-medium ${isActive ? 'bg-indigo-100 text-indigo-800 dark:bg-indigo-900/50 dark:text-indigo-200' : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800'}`

function Shell() {
  const { user, signOut } = useAuth()
  const { workspaces, current, select } = useWorkspace()
  const [dark, toggle] = useDarkMode()

  return (
    <div className="min-h-screen">
      <header className="flex flex-wrap items-center gap-4 border-b border-slate-200 bg-white px-6 py-3 dark:border-slate-800 dark:bg-slate-900">
        <span className="font-semibold">Glassbox</span>
        <nav aria-label="Main" className="flex gap-1">
          <NavLink to="/documents" className={navClass}>Documents</NavLink>
          <NavLink to="/chat" className={navClass}>Chat</NavLink>
        </nav>
        <select aria-label="Workspace" className="input w-auto" value={current?.id ?? ''} onChange={(e) => select(e.target.value)}>
          {workspaces.map((w) => (
            <option key={w.id} value={w.id}>{w.name}</option>
          ))}
        </select>
        <span className="ml-auto text-sm text-slate-500">{user?.email}</span>
        <button className="text-sm hover:underline" onClick={toggle} aria-pressed={dark}>{dark ? 'Light' : 'Dark'} mode</button>
        <button className="text-sm hover:underline" onClick={signOut}>Sign out</button>
      </header>
      <main className="mx-auto max-w-5xl p-6">
        {/* Remount pages on workspace change so no state from one workspace leaks into another's view. */}
        <Outlet key={current?.id} />
      </main>
    </div>
  )
}

function Layout() {
  const { user, loading } = useAuth()
  if (loading) return <p className="p-8 text-sm text-slate-500">Loading…</p>
  if (!user) return <Navigate to="/login" replace />
  return (
    <WorkspaceProvider>
      <Shell />
    </WorkspaceProvider>
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
              <Route path="/chat" element={<Chat />} />
              <Route path="*" element={<Navigate to="/documents" replace />} />
            </Route>
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  )
}
