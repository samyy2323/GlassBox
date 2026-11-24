import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { installStaticDemo } from './staticDemo'

const recorded = { 'GET /api/experiments': [{ id: 'e1' }], 'GET /api/documents?limit=200': { items: [] }, 'GET /api/traces': { items: [] } }

describe('the recorded demo', () => {
  let realFetch: ReturnType<typeof vi.fn>
  let win: { fetch: typeof fetch; location: { href: string; origin: string } }
  beforeEach(() => {
    const store = new Map<string, string>()
    vi.stubGlobal('sessionStorage', { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v), removeItem: (k: string) => void store.delete(k) })
    realFetch = vi.fn(async () => new Response(JSON.stringify(recorded))) // what serves demo-data/api.json
    win = { fetch: realFetch as unknown as typeof fetch, location: { href: 'http://localhost/GlassBox/', origin: 'http://localhost' } }
    vi.stubGlobal('window', win)
    installStaticDemo()
  })
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  const call = (path: string, method = 'GET') => win.fetch(path, { method })

  it('answers a recorded read, with or without its query string, and says so when a page was not recorded', async () => {
    expect(await (await call('/api/experiments')).json()).toEqual([{ id: 'e1' }])
    expect((await call('/api/documents?limit=200')).status).toBe(200)
    expect((await call('/api/traces?limit=30')).status).toBe(200) // falls back to the path alone
    const missing = await call('/api/experiments/unknown')
    expect(missing.status).toBe(404)
    expect((await missing.json()).detail).toMatch(/not recorded/)
  })

  it('refuses every write with an explanation instead of pretending it worked', async () => {
    for (const method of ['POST', 'PUT', 'PATCH', 'DELETE']) {
      const res = await call('/api/documents', method)
      expect(res.status).toBe(403)
      expect((await res.json()).detail).toMatch(/recorded demo/)
    }
    expect((await call('/api/chat', 'POST')).status).toBe(403)
  })

  it('signs in as the demo viewer only after the demo button, keeps the session for a reload and ends it on sign-out', async () => {
    expect((await call('/api/auth/refresh', 'POST')).status).toBe(401)
    expect((await (await call('/api/auth/demo')).json()).enabled).toBe(true)
    const signedIn = await call('/api/auth/demo', 'POST')
    expect((await signedIn.json()).user.email).toBe('demo@glassbox.local')
    expect((await call('/api/auth/refresh', 'POST')).status).toBe(200)
    expect((await call('/api/auth/logout', 'POST')).status).toBe(204)
    expect((await call('/api/auth/refresh', 'POST')).status).toBe(401)
    expect((await call('/api/auth/login', 'POST')).status).toBe(403) // no real accounts in the recording
  })

  it('leaves requests that are not for /api/ alone', async () => {
    await win.fetch('/assets/app.js')
    expect(realFetch).toHaveBeenLastCalledWith('/assets/app.js', undefined)
  })
})
