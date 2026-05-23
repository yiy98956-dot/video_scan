import { useState, useEffect, useCallback } from 'react'
import { Link } from 'react-router-dom'

const BASE = '/api'

const getToken = () => {
  try { return localStorage.getItem('film_horizon_access_token') } catch { return null }
}

const adminFetch = (url: string, options?: RequestInit) => {
  const t = getToken()
  return fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(t ? { Authorization: `Bearer ${t}` } : {}),
      ...options?.headers,
    },
  }).then(r => {
    if (r.status === 403) throw new Error('无管理员权限')
    if (!r.ok) throw new Error('请求失败')
    return r.json()
  })
}

interface UserItem {
  id: number
  username: string
  nickname: string
  avatarUrl: string
  role: string
  banned: number
  mutedUntil: string | null
  createTime: string
}

export default function AdminUsers() {
  const [users, setUsers] = useState<UserItem[]>([])
  const [total, setTotal] = useState(0)
  const [pages, setPages] = useState(0)
  const [page, setPage] = useState(1)
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const loadUsers = useCallback(async (p: number, kw?: string) => {
    setLoading(true)
    setError('')
    try {
      const params = new URLSearchParams({ page: String(p), size: '20' })
      if (kw) params.set('keyword', kw)
      const data = await adminFetch(`${BASE}/admin/users?${params}`)
      setUsers(data.items || [])
      setTotal(data.total || 0)
      setPages(data.pages || 0)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { loadUsers(1) }, [loadUsers])

  const onSearch = () => {
    setPage(1)
    loadUsers(1, keyword)
  }

  const onBan = async (userId: number, ban: boolean) => {
    try {
      await adminFetch(`${BASE}/admin/users/${userId}/${ban ? 'ban' : 'unban'}`, { method: 'POST' })
      loadUsers(page, keyword)
    } catch (err) {
      alert(err instanceof Error ? err.message : '操作失败')
    }
  }

  const onMute = async (userId: number, minutes: number) => {
    try {
      await adminFetch(`${BASE}/admin/users/${userId}/mute?durationMinutes=${minutes}`, { method: 'POST' })
      loadUsers(page, keyword)
    } catch (err) {
      alert(err instanceof Error ? err.message : '操作失败')
    }
  }

  const isMuted = (mutedUntil: string | null) => {
    if (!mutedUntil) return false
    return new Date(mutedUntil) > new Date()
  }

  const formatTime = (t: string | null) => {
    if (!t) return '-'
    return new Date(t).toLocaleString('zh-CN')
  }

  return (
    <div className="min-h-screen pt-24 px-4 sm:px-6 lg:px-8">
      <div className="max-w-6xl mx-auto">
        {/* 顶部导航 */}
        <div className="mb-8 animate-fade-up">
          <div className="flex items-center gap-3 mb-2">
            <Link to="/admin/categories" className="text-gold/70 hover:text-gold text-sm transition-colors">
              ← 分类管理
            </Link>
          </div>
          <h1 className="text-2xl text-text font-medium">用户管理</h1>
          <p className="text-text-secondary text-sm mt-1.5">查看、封禁、禁言用户</p>
        </div>

        {/* 搜索栏 */}
        <div className="flex gap-2 mb-6 animate-fade-up" style={{ animationDelay: '0.1s' }}>
          <input
            type="text"
            placeholder="搜索用户名或昵称..."
            value={keyword}
            onChange={e => setKeyword(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && onSearch()}
            className="flex-1 bg-surface border border-border rounded-lg px-4 py-2 text-sm text-text
                       placeholder:text-text-secondary/50 focus:outline-none focus:border-gold/40 transition-colors"
          />
          <button onClick={onSearch}
            className="px-5 py-2 bg-gold/10 text-gold border border-gold/25 rounded-lg text-sm
                       hover:bg-gold/20 transition-colors">
            搜索
          </button>
        </div>

        {/* 统计 */}
        {!loading && !error && (
          <div className="text-xs text-text-secondary mb-4">
            共 {total} 位用户
          </div>
        )}

        {/* 错误 */}
        {error && (
          <div className="text-center py-12">
            <p className="text-red-400 mb-4">{error}</p>
            <button onClick={() => loadUsers(page, keyword)}
              className="px-4 py-2 bg-surface border border-border rounded-lg text-sm text-text hover:border-gold/30 transition-colors">
              重试
            </button>
          </div>
        )}

        {/* 用户表格 */}
        {!loading && !error && (
          <div className="overflow-x-auto animate-fade-up" style={{ animationDelay: '0.15s' }}>
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-text-secondary text-xs">
                  <th className="text-left py-3 px-3 font-medium">ID</th>
                  <th className="text-left py-3 px-3 font-medium">用户名</th>
                  <th className="text-left py-3 px-3 font-medium">昵称</th>
                  <th className="text-left py-3 px-3 font-medium">角色</th>
                  <th className="text-left py-3 px-3 font-medium">状态</th>
                  <th className="text-left py-3 px-3 font-medium">禁言</th>
                  <th className="text-left py-3 px-3 font-medium">注册时间</th>
                  <th className="text-right py-3 px-3 font-medium">操作</th>
                </tr>
              </thead>
              <tbody>
                {users.map(u => (
                  <tr key={u.id} className="border-b border-border/50 hover:bg-surface/50 transition-colors">
                    <td className="py-3 px-3 text-text-secondary">{u.id}</td>
                    <td className="py-3 px-3 text-text">{u.username}</td>
                    <td className="py-3 px-3 text-text">{u.nickname || '-'}</td>
                    <td className="py-3 px-3">
                      <span className={`px-2 py-0.5 rounded text-xs ${
                        u.role === 'admin' ? 'bg-gold/15 text-gold' : 'bg-surface-2 text-text-secondary'
                      }`}>
                        {u.role === 'admin' ? '管理员' : '用户'}
                      </span>
                    </td>
                    <td className="py-3 px-3">
                      <span className={`px-2 py-0.5 rounded text-xs ${
                        u.banned === 1 ? 'bg-red-500/15 text-red-400' : 'bg-green-500/15 text-green-400'
                      }`}>
                        {u.banned === 1 ? '已封禁' : '正常'}
                      </span>
                    </td>
                    <td className="py-3 px-3 text-text-secondary text-xs">
                      {u.mutedUntil && isMuted(u.mutedUntil)
                        ? <span className="text-yellow-400">禁言至 {formatTime(u.mutedUntil)}</span>
                        : <span className="text-text-secondary/50">-</span>
                      }
                    </td>
                    <td className="py-3 px-3 text-text-secondary text-xs">{formatTime(u.createTime)}</td>
                    <td className="py-3 px-3 text-right">
                      {u.role !== 'admin' && (
                        <div className="flex items-center justify-end gap-1.5">
                          {u.banned === 1 ? (
                            <button onClick={() => onBan(u.id, false)}
                              className="px-2.5 py-1 text-xs bg-green-500/10 text-green-400 border border-green-500/20 rounded
                                         hover:bg-green-500/20 transition-colors">
                              解封
                            </button>
                          ) : (
                            <button onClick={() => onBan(u.id, true)}
                              className="px-2.5 py-1 text-xs bg-red-500/10 text-red-400 border border-red-500/20 rounded
                                         hover:bg-red-500/20 transition-colors">
                              封禁
                            </button>
                          )}
                          <select
                            onChange={e => {
                              const val = Number(e.target.value)
                              if (val > 0) onMute(u.id, val)
                              e.target.value = ''
                            }}
                            className="px-2 py-1 text-xs bg-surface border border-border rounded text-text
                                       focus:outline-none focus:border-gold/40 cursor-pointer"
                            defaultValue=""
                          >
                            <option value="" disabled>禁言</option>
                            <option value="10">10分钟</option>
                            <option value="60">1小时</option>
                            <option value="360">6小时</option>
                            <option value="1440">1天</option>
                            <option value="10080">7天</option>
                            <option value="43200">30天</option>
                            <option value="0">解禁</option>
                          </select>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
                {users.length === 0 && (
                  <tr>
                    <td colSpan={8} className="text-center py-12 text-text-secondary">暂无用户数据</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )}

        {/* 加载中 */}
        {loading && (
          <div className="flex justify-center py-12">
            <div className="w-6 h-6 border-2 border-gold/30 border-t-gold rounded-full animate-spin" />
          </div>
        )}

        {/* 分页 */}
        {pages > 1 && (
          <div className="flex justify-center items-center gap-2 mt-8">
            <button
              onClick={() => { setPage(p => Math.max(1, p - 1)); loadUsers(Math.max(1, page - 1), keyword) }}
              disabled={page <= 1}
              className="px-3 py-1.5 text-xs bg-surface border border-border rounded text-text
                         disabled:opacity-30 hover:border-gold/30 transition-colors"
            >
              上一页
            </button>
            <span className="text-xs text-text-secondary px-3">
              {page} / {pages}
            </span>
            <button
              onClick={() => { setPage(p => Math.min(pages, p + 1)); loadUsers(Math.min(pages, page + 1), keyword) }}
              disabled={page >= pages}
              className="px-3 py-1.5 text-xs bg-surface border border-border rounded text-text
                         disabled:opacity-30 hover:border-gold/30 transition-colors"
            >
              下一页
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
