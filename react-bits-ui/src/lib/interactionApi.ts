import { BASE } from './api'

function getToken(): string | null {
  try { return localStorage.getItem('film_horizon_access_token') } catch { return null }
}

function authHeaders(): Record<string, string> {
  const t = getToken()
  return t ? { 'Authorization': `Bearer ${t}` } : {}
}

async function fetchJSON<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init)
  const text = await res.text()
  if (!text) throw new Error(`请求失败 (${res.status})`)
  let json: any
  try { json = JSON.parse(text) } catch {
    throw new Error(`服务器返回异常: ${text.slice(0, 100)}`)
  }
  if (json.code !== 200) throw new Error(json.message || '请求失败')
  return json.data as T
}

export interface LikeResult { isLiked: boolean; likeCount: number }
export interface FavoriteResult { isFavorited: boolean; collectCount: number }

export const interactionApi = {
  toggleLike: (videoId: number, source?: string) =>
    fetchJSON<LikeResult>(`${BASE}/videos/${videoId}/like${source ? `?source=${encodeURIComponent(source)}` : ''}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
    }),

  toggleFavorite: (videoId: number, source?: string) =>
    fetchJSON<FavoriteResult>(`${BASE}/videos/${videoId}/favorite${source ? `?source=${encodeURIComponent(source)}` : ''}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
    }),

  getLikes: (page = 1, size = 20) =>
    fetchJSON<{ items: any[]; total: number }>(`${BASE}/user/likes?page=${page}&size=${size}`, {
      headers: { ...authHeaders() },
    }),

  getFavorites: (page = 1, size = 20) =>
    fetchJSON<{ items: any[]; total: number }>(`${BASE}/user/favorites?page=${page}&size=${size}`, {
      headers: { ...authHeaders() },
    }),
}

export interface CommentItem {
  id: number; videoId: number; userId: number; parentId: number; replyToUid: number
  content: string; likeCount: number; nickname: string; avatarUrl: string; createTime: string
  replies?: CommentItem[]
}

export const commentApi = {
  list: (videoId: number, page = 1, size = 10, sort = 'time') =>
    fetchJSON<{ items: CommentItem[]; total: number }>(
      `${BASE}/videos/${videoId}/comments?page=${page}&size=${size}&sort=${sort}`),

  create: (videoId: number, content: string, parentId?: number, replyToUid?: number) =>
    fetchJSON<CommentItem>(`${BASE}/videos/${videoId}/comments`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify({ content, parentId, replyToUid }),
    }),

  like: (commentId: number) =>
    fetchJSON<{ likeCount: number }>(`${BASE}/comments/${commentId}/like`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
    }),
}
