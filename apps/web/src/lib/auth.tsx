import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, onAuthLost, setAccessToken, setWorkspaceId, type AuthResponse, type User } from './api'

interface AuthState {
  user: User | null
  loading: boolean
  signIn: (email: string, password: string, register: boolean) => Promise<void>
  signOut: () => Promise<void>
}

const Ctx = createContext<AuthState | null>(null)
let initialRefresh: Promise<AuthResponse> | null = null

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)

  const accept = useCallback((r: AuthResponse) => {
    setAccessToken(r.accessToken)
    setUser(r.user)
  }, [])

  // On load, try to resume the session from the refresh cookie. The call is shared at module level:
  // refresh tokens rotate, so StrictMode's double effect must not send the same cookie twice
  // (the server treats a replayed token as theft and revokes the whole family).
  useEffect(() => {
    initialRefresh ??= api.refresh()
    initialRefresh
      .then(accept)
      .catch(() => undefined)
      .finally(() => setLoading(false))
  }, [accept])

  // If a later request cannot refresh the session, fall back to the login page.
  useEffect(() => {
    onAuthLost(() => {
      setAccessToken(null)
      setWorkspaceId(null)
      setUser(null)
    })
    return () => onAuthLost(null)
  }, [])

  const signIn = useCallback(
    async (email: string, password: string, register: boolean) =>
      accept(await (register ? api.register(email, password) : api.login(email, password))),
    [accept],
  )

  const signOut = useCallback(async () => {
    await api.logout().catch(() => undefined)
    setAccessToken(null)
    setWorkspaceId(null)
    setUser(null)
  }, [])

  return <Ctx.Provider value={{ user, loading, signIn, signOut }}>{children}</Ctx.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthState {
  const v = useContext(Ctx)
  if (!v) throw new Error('useAuth outside AuthProvider')
  return v
}
