// The access token lives in memory only. The refresh token is an httpOnly cookie the page never sees.
import { SseParser, type SseEvent } from './sse'

let accessToken: string | null = null
let workspaceId: string | null = null
let authLostHandler: (() => void) | null = null

export const setAccessToken = (t: string | null) => {
  accessToken = t
}
export const setWorkspaceId = (id: string | null) => {
  workspaceId = id
}
/** Called when the session can no longer be refreshed (the app then shows the login page). */
export const onAuthLost = (fn: (() => void) | null) => {
  authLostHandler = fn
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public retryAfterSeconds?: number,
  ) {
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
export type Role = 'OWNER' | 'EDITOR' | 'VIEWER'
export interface Workspace {
  id: string
  name: string
  role: Role
}
export type DocStatus = 'UPLOADED' | 'PROCESSING' | 'READY' | 'FAILED' | 'FAILED_UNSUPPORTED'
export interface DocumentDto {
  id: string
  title: string
  sourceType: string
  status: DocStatus
  error?: string
  version: number
  sizeBytes: number
  createdAt: string
}
export interface DocumentPage {
  items: DocumentDto[]
  nextCursor?: string
}
export interface UploadResult {
  document: DocumentDto
  duplicate: boolean
}
export interface Citation {
  label: string
  chunkId: string
  documentId: string
  title: string
  page?: number
  charStart: number
  charEnd: number
  text: string
}
export interface TraceSpan {
  stage: string
  millis: number
  detail?: Record<string, unknown>
}

const csrf = { 'X-Glassbox-CSRF': '1' }

async function toError(res: Response): Promise<ApiError> {
  let detail = res.statusText
  try {
    detail = (await res.json()).detail ?? detail // problem+json
  } catch {
    /* no body */
  }
  const ra = Number(res.headers.get('Retry-After'))
  return new ApiError(res.status, detail, Number.isFinite(ra) && ra > 0 ? ra : undefined)
}

function withHeaders(init: RequestInit, extra?: Record<string, string>): RequestInit {
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
  if (workspaceId) headers.set('X-Workspace-Id', workspaceId)
  for (const [k, v] of Object.entries(extra ?? {})) headers.set(k, v)
  return { ...init, headers, credentials: 'same-origin' }
}

// Access tokens last 15 minutes. One shared refresh serves every request that hits a 401 at the same time
// (refresh tokens rotate, so two concurrent refreshes would look like token theft to the server).
let refreshing: Promise<boolean> | null = null
export function refreshSession(): Promise<boolean> {
  refreshing ??= (async () => {
    try {
      const res = await fetch('/api/auth/refresh', { method: 'POST', headers: csrf, credentials: 'same-origin' })
      if (!res.ok) return false
      accessToken = ((await res.json()) as AuthResponse).accessToken
      return true
    } catch {
      return false
    } finally {
      refreshing = null
    }
  })()
  return refreshing
}

/** fetch with auth headers; on 401 it refreshes the session once and retries. */
export async function authedFetch(path: string, init: RequestInit = {}, extra?: Record<string, string>): Promise<Response> {
  let res = await fetch(path, withHeaders(init, extra))
  if (res.status === 401 && !path.startsWith('/api/auth/')) {
    if (await refreshSession()) {
      res = await fetch(path, withHeaders(init, extra))
    } else {
      authLostHandler?.()
    }
  }
  return res
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await authedFetch(path, init)
  if (!res.ok) throw await toError(res)
  return res.status === 204 ? (undefined as T) : res.json()
}

export const api = {
  register: (email: string, password: string) =>
    request<AuthResponse>('/api/auth/register', { method: 'POST', body: JSON.stringify({ email, password }) }),
  login: (email: string, password: string) =>
    request<AuthResponse>('/api/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }),
  refresh: () => request<AuthResponse>('/api/auth/refresh', { method: 'POST', headers: csrf }),
  logout: () => request<void>('/api/auth/logout', { method: 'POST', headers: csrf }),
  workspaces: () => request<Workspace[]>('/api/workspaces'),
  documents: (limit = 200) => request<DocumentPage>(`/api/documents?limit=${limit}`),
  upload: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<UploadResult>('/api/documents', { method: 'POST', body: form })
  },
  deleteDocument: (id: string) => request<void>(`/api/documents/${id}`, { method: 'DELETE' }),
}

/** Reads a server-sent event stream from a fetch response and calls onEvent for each frame. Resolves when it ends. */
async function readEvents(res: Response, onEvent: (e: SseEvent) => void): Promise<void> {
  if (!res.body) throw new ApiError(res.status, 'The server sent no stream')
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  const parser = new SseParser()
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    for (const ev of parser.push(decoder.decode(value, { stream: true }))) onEvent(ev)
  }
}

/** Streams one chat turn. Refusals (401/404/429...) reject with an ApiError before any event is delivered. */
export async function streamChat(question: string, onEvent: (e: SseEvent) => void, signal: AbortSignal): Promise<void> {
  const res = await authedFetch('/api/chat', { method: 'POST', body: JSON.stringify({ question }), signal }, { Accept: 'text/event-stream' })
  if (!res.ok) throw await toError(res)
  await readEvents(res, onEvent)
}

/** Live document status for the current workspace. Resolves when the stream closes; the caller reconnects. */
export async function streamDocumentEvents(onEvent: (e: SseEvent) => void, signal: AbortSignal): Promise<void> {
  const res = await authedFetch('/api/events', { signal }, { Accept: 'text/event-stream' })
  if (!res.ok) throw await toError(res)
  await readEvents(res, onEvent)
}
