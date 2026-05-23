const BASE = '/api'

export interface LoginRequest { username: string; password: string }
export interface RegisterRequest { username: string; password: string }
export interface AuthTokens { access_token: string; refresh_token: string }
export interface UserProfile {
  id: number
  username: string
  nickname: string
  avatarUrl: string
  role: string
  followerCount: number
  followingCount: number
  createTime: string
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  // 先把 options.headers 拆出来，避免 ...options 覆盖上层 headers
  const { headers: extraHeaders, ...rest } = options
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (extraHeaders) Object.assign(headers, extraHeaders as Record<string, string>)

  const res = await fetch(`${BASE}${path}`, { ...rest, headers })

  const text = await res.text()
  if (!text) {
    if (!res.ok) throw new Error(`请求失败 (${res.status})`)
    throw new Error('服务器返回为空')
  }

  let json: any
  try { json = JSON.parse(text) } catch {
    throw new Error(`服务器返回异常: ${text.slice(0, 100)}`)
  }

  if (json.code !== 200) throw new Error(json.message || '请求失败')
  return json.data as T
}

export const authApi = {
  login: (data: LoginRequest) =>
    request<{ accessToken: string; refreshToken: string; tokenType: string; expiresIn: number }>('/auth/login', {
      method: 'POST', body: JSON.stringify(data),
    }),

  register: (data: RegisterRequest) =>
    request<{ accessToken: string; refreshToken: string }>('/auth/register', {
      method: 'POST', body: JSON.stringify(data),
    }),

  refresh: (refreshToken: string) =>
    request<{ accessToken: string; refreshToken: string }>('/auth/refresh', {
      method: 'POST', body: JSON.stringify({ refreshToken }),
    }),

  getProfile: (token: string) =>
    request<UserProfile>('/user/profile', {
      headers: { 'Authorization': `Bearer ${token}` },
    }),

  updateProfile: (token: string, data: { nickname?: string; avatarUrl?: string }) =>
    request<UserProfile>('/user/profile', {
      method: 'POST', body: JSON.stringify(data),
      headers: { 'Authorization': `Bearer ${token}` },
    }),
}
