import { createContext, useContext, useState, useEffect, type ReactNode, useCallback } from 'react'
import { authApi, type UserProfile } from './authApi'

const TOKEN_KEY = 'film_horizon_access_token'
const REFRESH_KEY = 'film_horizon_refresh_token'

interface AuthState {
  user: UserProfile | null
  token: string | null
  loading: boolean
  login: (username: string, password: string) => Promise<void>
  register: (username: string, password: string) => Promise<void>
  logout: () => void
  refreshSession: () => Promise<void>
  refreshUser: () => Promise<void>
}

interface LoginResult {
  accessToken: string
  refreshToken: string
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserProfile | null>(null)
  const [token, setToken] = useState<string | null>(() => {
    try { return localStorage.getItem(TOKEN_KEY) } catch { return null }
  })
  const [loading, setLoading] = useState(true)

  const storeTokens = useCallback((t: LoginResult) => {
    try {
      localStorage.setItem(TOKEN_KEY, t.accessToken)
      localStorage.setItem(REFRESH_KEY, t.refreshToken)
    } catch { /* quota exceeded */ }
    setToken(t.accessToken)
  }, [])

  const clearTokens = useCallback(() => {
    try {
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(REFRESH_KEY)
    } catch { /* ignore */ }
    setToken(null)
    setUser(null)
  }, [])

  const login = useCallback(async (username: string, password: string) => {
    const res = await authApi.login({ username, password })
    storeTokens(res)
    const profile = await authApi.getProfile(res.accessToken)
    setUser(profile)
  }, [storeTokens])

  const register = useCallback(async (username: string, password: string) => {
    const res = await authApi.register({ username, password })
    storeTokens(res)
    const profile = await authApi.getProfile(res.accessToken)
    setUser(profile)
  }, [storeTokens])

  const logout = useCallback(() => {
    clearTokens()
  }, [clearTokens])

  const refreshSession = useCallback(async () => {
    const rt = (() => { try { return localStorage.getItem(REFRESH_KEY) } catch { return null } })()
    if (!rt) { clearTokens(); return }
    try {
      const res = await authApi.refresh(rt)
      storeTokens(res)
    } catch { clearTokens() }
  }, [clearTokens, storeTokens])

  const refreshUser = useCallback(async () => {
    const t = (() => { try { return localStorage.getItem(TOKEN_KEY) } catch { return null } })()
    if (!t) return
    try {
      const profile = await authApi.getProfile(t)
      setUser(profile)
    } catch { /* ignore */ }
  }, [])

  useEffect(() => {
    if (!token) { setLoading(false); return }
    authApi.getProfile(token)
      .then(setUser)
      .catch(() => {
        refreshSession().then(() => {
          const t = (() => { try { return localStorage.getItem(TOKEN_KEY) } catch { return null } })()
          if (t) authApi.getProfile(t).then(setUser).catch(clearTokens)
        })
      })
      .finally(() => setLoading(false))
  }, [])

  return (
    <AuthContext.Provider value={{ user, token, loading, login, register, logout, refreshSession, refreshUser }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
