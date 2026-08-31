// src/api/http.ts
// Thin fetch wrapper for static JSON mock data served from /mock-data/.

const BASE = '/mock-data'

const MIN_LATENCY = 150
const MAX_LATENCY = 400

function sleep(ms: number) {
  return new Promise((r) => setTimeout(r, ms))
}

function buildUrl(path: string, params?: Record<string, unknown>): string {
  const cleanPath = path.replace(/^\/+/, '').replace(/\.json$/, '')
  let url = `${BASE}/${cleanPath}.json`
  if (params && Object.keys(params).length > 0) {
    const search = new URLSearchParams()
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null) search.set(k, String(v))
    }
    const qs = search.toString()
    if (qs) url += `?${qs}`
  }
  return url
}

export async function httpGet<T>(path: string, params?: Record<string, unknown>): Promise<T> {
  await sleep(MIN_LATENCY + Math.random() * (MAX_LATENCY - MIN_LATENCY))
  const url = buildUrl(path, params)
  const res = await fetch(url, { method: 'GET' })
  if (!res.ok) {
    throw new Error(`[mock api] ${res.status} ${res.statusText} (${url})`)
  }
  return (await res.json()) as T
}

export async function httpPost<T>(path: string, body?: unknown): Promise<T> {
  await sleep(MIN_LATENCY + Math.random() * (MAX_LATENCY - MIN_LATENCY))
  const url = buildUrl(path)
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) {
    throw new Error(`[mock api] ${res.status} ${res.statusText} (${url})`)
  }
  return (await res.json()) as T
}
