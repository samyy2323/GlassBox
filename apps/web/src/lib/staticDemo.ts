/**
 * The recorded demo: a build of the real interface that needs no server. Every read the interface makes under /api/ is answered from `demo-data/api.json`, which
 * `deploy/record_demo.py` captured from a seeded Glassbox, and everything that would change something is refused with a plain explanation.
 * It is installed only when the app is built with VITE_STATIC_DEMO=1 (see main.tsx), so a normal build never carries any of this behaviour.
 */
const DEMO_EMAIL = 'demo@glassbox.local'
const SESSION_FLAG = 'gb-static-demo-session'
const READ_ONLY = 'This is a recorded demo, so asking questions, uploading and editing are switched off. Run Glassbox yourself (see the README) to use your own documents.'

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function problem(status: number, detail: string): Response {
  return new Response(JSON.stringify({ type: 'about:blank', title: 'Not available', status, detail }), { status, headers: { 'Content-Type': 'application/problem+json' } })
}

function flag(value?: boolean): boolean {
  try {
    if (value === true) sessionStorage.setItem(SESSION_FLAG, '1')
    if (value === false) sessionStorage.removeItem(SESSION_FLAG)
    return sessionStorage.getItem(SESSION_FLAG) === '1'
  } catch {
    return value === true // storage unavailable: the session lasts until the page is reloaded
  }
}

const session = () => json({ user: { id: '00000000-0000-0000-0000-00000000d3a0', email: DEMO_EMAIL }, accessToken: 'recorded-demo', expiresIn: 900 })

export function installStaticDemo(): void {
  const realFetch = window.fetch.bind(window)
  let recorded: Promise<Record<string, unknown>> | null = null
  const load = () => (recorded ??= realFetch(`${import.meta.env.BASE_URL}demo-data/api.json`).then((r) => r.json() as Promise<Record<string, unknown>>))

  window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const href = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const url = new URL(href, window.location.href)
    if (url.origin !== window.location.origin || !url.pathname.startsWith('/api/')) return realFetch(input, init)

    const method = (init?.method ?? (typeof input === 'object' && 'method' in input ? input.method : 'GET')).toUpperCase()
    const path = url.pathname

    if (path === '/api/auth/demo') return method === 'POST' ? (flag(true), session()) : json({ enabled: true, email: DEMO_EMAIL })
    if (path === '/api/auth/refresh') return flag() ? session() : problem(401, 'No session')
    if (path === '/api/auth/logout') return (flag(false), new Response(null, { status: 204 }))
    if (path.startsWith('/api/auth/')) return problem(403, 'Accounts are switched off in the recorded demo. Press "Try the demo", or run Glassbox yourself to create one.')
    if (path === '/api/events') return new Response(new ReadableStream({ start() { /* the live feed stays open and silent */ } }), { headers: { 'Content-Type': 'text/event-stream' } })
    if (method !== 'GET') return problem(403, READ_ONLY)

    const data = await load()
    const hit = data[`GET ${path}${url.search}`] ?? data[`GET ${path}`]
    return hit === undefined ? problem(404, 'This page was not recorded in the demo.') : json(hit)
  }
}
