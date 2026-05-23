import type { VideoItemVO, VideoDetailVO, PageData, SearchResult, BrowseParams, CategoryNode } from './types'
import { processResponse } from './crypto'

// 加密密钥（应该与后端配置一致）
const CRYPTO_SECRET = import.meta.env.VITE_CRYPTO_SECRET || 'defaultSecretKeyForDevelopmentOnly'
// 是否启用加密
const CRYPTO_ENABLED = import.meta.env.VITE_CRYPTO_ENABLED === 'true'

export const BASE = '/api'

// ── 内存缓存 ──
const cache = new Map<string, { data: unknown; ttl: number }>()
const CACHE_TTL = 60_000

function getCached<T>(key: string): T | null {
  const entry = cache.get(key)
  if (!entry) return null
  if (Date.now() > entry.ttl) { cache.delete(key); return null }
  return entry.data as T
}

function setCache(key: string, data: unknown) {
  cache.set(key, { data, ttl: Date.now() + CACHE_TTL })
  if (cache.size > 50) {
    const first = cache.keys().next().value
    if (first) cache.delete(first)
  }
}

// ── 持久缓存 (sessionStorage, 刷新不丢) ──
function getPersistent<T>(key: string): T | null {
  try {
    const raw = sessionStorage.getItem('api:' + key)
    if (!raw) return null
    const { data, expiry } = JSON.parse(raw)
    if (Date.now() > expiry) { sessionStorage.removeItem('api:' + key); return null }
    return data as T
  } catch { return null }
}

function setPersistent(key: string, data: unknown, ttl = CACHE_TTL) {
  try { sessionStorage.setItem('api:' + key, JSON.stringify({ data, expiry: Date.now() + ttl })) }
  catch { /* quota */ }
}

// ── 请求去重 ──
const inflight = new Map<string, Promise<unknown>>()

function cacheKey(path: string, params?: Record<string, unknown>): string {
  return params ? path + '?' + JSON.stringify(params) : path
}

async function get<T>(path: string, params?: Record<string, string | number | undefined | null>, noCache = false): Promise<T> {
  const key = cacheKey(path, params as Record<string, unknown>)

  // 列表类接口不使用缓存（分页数据会变化）
  if (!noCache) {
    // 1. 内存缓存
    const mem = getCached<T>(key)
    if (mem) return mem

    // 2. 持久缓存
    const per = getPersistent<T>(key)
    if (per) { setCache(key, per); return per }
  }

  // 3. 去重：相同 key 正在请求则不重复发
  const existing = inflight.get(key)
  if (existing) return existing as Promise<T>

  let url = `${BASE}${path}`
  if (params) {
    const qs = Object.entries(params)
      .filter(([, v]) => v !== undefined && v !== null && v !== '')
      .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`)
      .join('&')
    if (qs) url += `?${qs}`
  }

  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 15_000)

  const promise = (async () => {
    try {
      console.log('[API] fetching:', url)
      const res = await fetch(url, { headers: { 'Content-Type': 'application/json' }, signal: controller.signal })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const text = await res.text()
      if (!text) throw new Error('服务器返回为空')
      const json = JSON.parse(text)
      if (json.code !== undefined && json.code !== 200) throw new Error(json.message || 'API error')
      let data = (json.code !== undefined ? json.data : json) as T

      // 处理加密响应
      if (CRYPTO_ENABLED && json.encrypted) {
        data = await processResponse(json, CRYPTO_SECRET) as T
      }

      if (!noCache) {
        setCache(key, data)
        setPersistent(key, data)
      }
      return data
    } finally {
      clearTimeout(timeout)
      inflight.delete(key)
    }
  })()

  inflight.set(key, promise)
  return promise
}

export const videoApi = {
  list: (p?: BrowseParams) =>
    get<PageData<VideoItemVO>>('/videos', p as Record<string, string | number | undefined>, true),
  detail: (vodId: number, source?: string) =>
    get<VideoDetailVO>(`/videos/${vodId}`, source ? { source } : undefined),
  types: () => get<string[]>('/videos/types'),
  categories: (type?: string) =>
    get<string[]>('/videos/categories', type ? { type } : undefined),
}

export const searchApi = {
  search: (keyword: string, page = 1, size = 20) =>
    get<SearchResult>('/search', { keyword, page, size }),
}

export const categoryApi = {
  tree: () => get<{ items: CategoryNode[]; total?: number }>('/category/tree-with-counts').then(r => r.items || []),
}

export const historyApi = {
  report: (videoId: number, progress: number, duration?: number, source?: string) => {
    const t = (() => { try { return localStorage.getItem('film_horizon_access_token') } catch { return null } })()
    return fetch(`${BASE}/history/report`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(t ? { 'Authorization': `Bearer ${t}` } : {}) },
      body: JSON.stringify({ videoId, progress, duration, source }),
    })
  },
  getProgress: (videoId: number): Promise<number> => {
    const t = (() => { try { return localStorage.getItem('film_horizon_access_token') } catch { return null } })()
    return fetch(`${BASE}/history/progress/${videoId}`, {
      headers: { ...(t ? { 'Authorization': `Bearer ${t}` } : {}) },
    }).then(r => r.text()).then(text => {
      if (!text) return 0
      const json = JSON.parse(text)
      if (json.code === 200) return json.data || 0
      return 0
    }).catch(() => 0)
  },
}

export function coverUrl(url: string): string {
  if (!url) return ''
  if (url.startsWith('http')) return url
  if (url.startsWith('/api/')) return url
  return `${BASE}/movies/proxy?url=${encodeURIComponent(url)}`
}

export function streamProxyUrl(rawUrl: string): string {
  return `${BASE}/go/proxy?url=${encodeURIComponent(rawUrl)}`
}
