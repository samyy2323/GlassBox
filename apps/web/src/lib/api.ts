// The access token lives in memory only. The refresh token is an httpOnly cookie the page never sees.
let accessToken: string | null = null

export const setAccessToken = (t: string | null) => {
  accessToken = t
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

export interface User {
  id: string
  email: string
}
export interface AuthResponse {
  user: User
  accessToken: string
  expiresIn: number
}
export interface Workspace {
  id: string
  name: string
  role: 'OWNER' | 'EDITOR' | 'VIEWER'
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body) headers.set('Content-Type', 'application/json')
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  const res = await fetch(path, { ...init, headers, credentials: 'same-origin' })
  if (!res.ok) {
    let detail = res.statusText
    try {
      detail = (await res.json()).detail ?? detail // problem+json
    } catch {
      /* no body */
    }
    throw new ApiError(res.status, detail)
  }
  return res.status === 204 ? (undefined as T) : res.json()
}

const csrf = { 'X-Glassbox-CSRF': '1' }

export const api = {
  register: (email: string, password: string) =>
    request<AuthResponse>('/api/auth/register', { method: 'POST', body: JSON.stringify({ email, password }) }),
  login: (email: string, password: string) =>
    request<AuthResponse>('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }),
  refresh: () => request<AuthResponse>('/api/auth/refresh', { method: 'POST', headers: csrf }),
  logout: () => request<void>('/api/auth/logout', { method: 'POST', headers: csrf }),
  workspaces: () => request<Workspace[]>('/api/workspaces'),
}
