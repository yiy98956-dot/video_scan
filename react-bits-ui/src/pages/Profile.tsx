import { useState, useEffect, useRef } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/auth'
import { interactionApi } from '../lib/interactionApi'
import { BASE } from '../lib/api'
import LoadingSkeleton from '../components/LoadingSkeleton'

type TabKey = 'likes' | 'favorites' | 'history' | 'settings'

export default function Profile() {
  const { user, token, loading: authLoading, logout, refreshUser } = useAuth()
  const navigate = useNavigate()
  const [tab, setTab] = useState<TabKey>('likes')
  const [items, setItems] = useState<any[]>([])
  const [loading, setLoading] = useState(false)

  // 浏览记录删除相关
  const [selectMode, setSelectMode] = useState(false)
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set())
  const [deleting, setDeleting] = useState(false)

  // 设置相关状态
  const [nickname, setNickname] = useState('')
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [updating, setUpdating] = useState(false)
  const [message, setMessage] = useState('')
  const fileInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (!authLoading && !user) navigate('/auth')
  }, [user, authLoading, navigate])

  useEffect(() => {
    if (user) {
      setNickname(user.nickname || '')
    }
  }, [user])

  useEffect(() => {
    if (!token || tab === 'settings') return
    setSelectMode(false)
    setSelectedIds(new Set())
    setLoading(true)
    const fetchData = async () => {
      try {
        if (tab === 'likes') {
          const res = await interactionApi.getLikes()
          setItems(res.items || [])
        } else if (tab === 'favorites') {
          const res = await interactionApi.getFavorites()
          setItems(res.items || [])
        } else if (tab === 'history') {
          const res = await fetch(`${BASE}/history?page=1&size=50`, {
            headers: { 'Authorization': `Bearer ${token}` },
            cache: 'no-store',
          })
          const text = await res.text()
          if (text) {
            try {
              const json = JSON.parse(text)
              if (json.code === 200) setItems(json.data?.items || [])
            } catch {}
          }
        }
      } catch (e) {
        console.error('Failed to load', tab, e)
        setItems([])
      } finally {
        setLoading(false)
      }
    }
    fetchData()
  }, [tab, token])

  // 更新个人信息
  const handleUpdateProfile = async () => {
    if (!token) return
    setUpdating(true)
    setMessage('')
    try {
      const body: any = {}
      if (nickname !== user?.nickname) body.nickname = nickname
      if (currentPassword && newPassword) {
        if (newPassword !== confirmPassword) {
          setMessage('两次输入的新密码不一致')
          setUpdating(false)
          return
        }
        body.password = currentPassword
        body.newPassword = newPassword
      }

      if (Object.keys(body).length === 0) {
        setMessage('没有需要更新的内容')
        setUpdating(false)
        return
      }

      const res = await fetch(`${BASE}/user/profile`, {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`,
        },
        body: JSON.stringify(body),
      })
      const data = await res.json()
      if (data.code === 200) {
        setMessage('更新成功')
        setCurrentPassword('')
        setNewPassword('')
        setConfirmPassword('')
        refreshUser()
      } else {
        setMessage(data.message || '更新失败')
      }
    } catch (e) {
      setMessage('网络错误')
    } finally {
      setUpdating(false)
    }
  }

  // 上传头像
  const handleAvatarClick = () => {
    fileInputRef.current?.click()
  }

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file || !token) return

    setUpdating(true)
    setMessage('')
    try {
      const formData = new FormData()
      formData.append('file', file)

      const res = await fetch(`${BASE}/user/avatar`, {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`,
        },
        body: formData,
      })
      const data = await res.json()
      if (data.code === 200) {
        // 更新头像 URL 到用户资料
        const updateRes = await fetch(`${BASE}/user/profile`, {
          method: 'PUT',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${token}`,
          },
          body: JSON.stringify({ avatarUrl: data.data.avatarUrl }),
        })
        const updateData = await updateRes.json()
        if (updateData.code === 200) {
          setMessage('头像上传成功')
          refreshUser()
        } else {
          setMessage('头像上传成功，但更新资料失败')
        }
      } else {
        setMessage(data.message || '上传失败')
      }
    } catch (e) {
      setMessage('上传失败')
    } finally {
      setUpdating(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  // 删除单条浏览记录
  const deleteSingle = async (historyId: number, e: React.MouseEvent) => {
    e.stopPropagation()
    if (!token || deleting) return
    setDeleting(true)
    try {
      const res = await fetch(`${BASE}/history/${historyId}`, {
        method: 'DELETE',
        headers: { 'Authorization': `Bearer ${token}` },
      })
      if (res.ok) {
        setItems(prev => prev.filter((item: any) => item.historyId !== historyId))
      }
    } catch {}
    setDeleting(false)
  }

  // 批量删除浏览记录
  const deleteSelected = async () => {
    if (!token || deleting || selectedIds.size === 0) return
    setDeleting(true)
    try {
      const res = await fetch(`${BASE}/history/batch-delete`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
        body: JSON.stringify([...selectedIds]),
      })
      if (res.ok) {
        setItems(prev => prev.filter((item: any) => !selectedIds.has(item.historyId)))
        setSelectedIds(new Set())
        setSelectMode(false)
      }
    } catch {}
    setDeleting(false)
  }

  // 切换选中
  const toggleSelect = (historyId: number, e: React.MouseEvent) => {
    e.stopPropagation()
    setSelectedIds(prev => {
      const next = new Set(prev)
      if (next.has(historyId)) next.delete(historyId)
      else next.add(historyId)
      return next
    })
  }

  if (authLoading) return <div className="min-h-screen flex items-center justify-center"><LoadingSkeleton variant="detail" /></div>
  if (!user) return null

  const tabs: { key: TabKey; label: string }[] = [
    { key: 'likes', label: '我的点赞' },
    { key: 'favorites', label: '我的收藏' },
    { key: 'history', label: '浏览记录' },
    { key: 'settings', label: '个人设置' },
  ]

  return (
    <div className="min-h-screen">
      {/* Header */}
      <div className="relative pt-20 pb-10 px-4 sm:px-6 lg:px-8 overflow-hidden">
        <div className="hero-glow" />
        <div className="absolute inset-0" style={{
          backgroundImage: 'radial-gradient(rgba(201,169,110,0.03) 1px, transparent 1px)',
          backgroundSize: '40px 40px',
        }} />
        <div className="max-w-4xl mx-auto relative z-10">
          <div className="flex flex-col sm:flex-row items-center sm:items-end gap-6">
            {/* 头像 - 可点击上传 */}
            <div 
              onClick={handleAvatarClick}
              className="w-20 h-20 rounded-full bg-surface-2 border border-border overflow-hidden flex-shrink-0
                shadow-[0_4px_20px_rgba(0,0,0,0.3)] cursor-pointer hover:border-gold/40 transition-colors relative group"
            >
              {user.avatarUrl ? (
                <img src={user.avatarUrl} alt="" className="w-full h-full object-cover" />
              ) : (
                <div className="w-full h-full flex items-center justify-center text-2xl text-text-secondary">
                  {user.nickname?.charAt(0)?.toUpperCase() || user.username.charAt(0).toUpperCase()}
                </div>
              )}
              <div className="absolute inset-0 bg-black/50 flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity">
                <span className="text-xs text-white">更换</span>
              </div>
              <input
                ref={fileInputRef}
                type="file"
                accept="image/jpeg,image/png,image/gif,image/webp"
                onChange={handleFileChange}
                className="hidden"
              />
            </div>
            <div className="text-center sm:text-left">
              <h1 className="text-xl sm:text-2xl font-bold text-text">{user.nickname || user.username}</h1>
              <p className="text-sm text-text-secondary mt-1">@{user.username}</p>
              {user.role === 'admin' && (
                <span className="inline-block mt-2 px-2 py-0.5 rounded-full bg-gold/10 text-gold text-[10px]">管理员</span>
              )}
            </div>
            <button onClick={logout}
              className="sm:ml-auto text-xs text-text-secondary hover:text-text transition-colors px-4 py-2 rounded-lg border border-border hover:border-gold/20">
              退出登录
            </button>
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 mb-6">
        <div className="flex gap-1 bg-surface rounded-xl p-1 border border-border">
          {tabs.map(t => (
            <button key={t.key} onClick={() => setTab(t.key)}
              className={`flex-1 py-2.5 text-xs rounded-lg transition-all duration-200 ${
                tab === t.key
                  ? 'bg-gold/10 text-gold shadow-sm'
                  : 'text-text-secondary hover:text-text'
              }`}>
              {t.label}
            </button>
          ))}
        </div>
      </div>

      {/* Content */}
      <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8">
        {tab === 'settings' ? (
          <div className="max-w-md mx-auto space-y-6">
            {message && (
              <div className={`text-sm text-center py-2 px-4 rounded-lg ${
                message.includes('成功') ? 'bg-green-500/10 text-green-400' : 'bg-red-500/10 text-red-400'
              }`}>
                {message}
              </div>
            )}
            
            {/* 昵称设置 */}
            <div className="bg-surface rounded-xl p-6 border border-border">
              <h3 className="text-sm font-medium text-text mb-4">修改昵称</h3>
              <div className="space-y-4">
                <div>
                  <label className="block text-xs text-text-secondary mb-1.5">昵称</label>
                  <input
                    type="text"
                    value={nickname}
                    onChange={(e) => setNickname(e.target.value)}
                    className="w-full bg-surface-2 border border-border rounded-lg px-4 py-2.5 text-sm text-text
                      placeholder:text-text-secondary/50 focus:outline-none focus:border-gold/40 transition-colors"
                    placeholder="输入新昵称"
                  />
                </div>
              </div>
            </div>

            {/* 密码设置 */}
            <div className="bg-surface rounded-xl p-6 border border-border">
              <h3 className="text-sm font-medium text-text mb-4">修改密码</h3>
              <div className="space-y-4">
                <div>
                  <label className="block text-xs text-text-secondary mb-1.5">当前密码</label>
                  <input
                    type="password"
                    value={currentPassword}
                    onChange={(e) => setCurrentPassword(e.target.value)}
                    className="w-full bg-surface-2 border border-border rounded-lg px-4 py-2.5 text-sm text-text
                      placeholder:text-text-secondary/50 focus:outline-none focus:border-gold/40 transition-colors"
                    placeholder="输入当前密码"
                  />
                </div>
                <div>
                  <label className="block text-xs text-text-secondary mb-1.5">新密码</label>
                  <input
                    type="password"
                    value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)}
                    className="w-full bg-surface-2 border border-border rounded-lg px-4 py-2.5 text-sm text-text
                      placeholder:text-text-secondary/50 focus:outline-none focus:border-gold/40 transition-colors"
                    placeholder="输入新密码"
                  />
                </div>
                <div>
                  <label className="block text-xs text-text-secondary mb-1.5">确认新密码</label>
                  <input
                    type="password"
                    value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)}
                    className="w-full bg-surface-2 border border-border rounded-lg px-4 py-2.5 text-sm text-text
                      placeholder:text-text-secondary/50 focus:outline-none focus:border-gold/40 transition-colors"
                    placeholder="再次输入新密码"
                  />
                </div>
              </div>
            </div>

            <button
              onClick={handleUpdateProfile}
              disabled={updating}
              className="w-full py-3 bg-gold/10 text-gold border border-gold/25 rounded-lg text-sm
                hover:bg-gold/20 transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {updating ? '保存中...' : '保存修改'}
            </button>
          </div>
        ) : loading ? (
          <div className="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-5 gap-3">
            {Array.from({ length: 10 }).map((_, i) => (
              <div key={i} className="aspect-[3/4] rounded-lg bg-surface-2 animate-pulse" />
            ))}
          </div>
        ) : items.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-20">
            <p className="text-text-secondary text-sm">
              {tab === 'likes' ? '还没有点赞过视频' :
               tab === 'favorites' ? '还没有收藏过视频' : '还没有观看记录'}
            </p>
          </div>
        ) : (
          <>
            {tab === 'history' && (
              <div className="flex items-center justify-between mb-4">
                <button
                  onClick={() => {
                    setSelectMode(!selectMode)
                    setSelectedIds(new Set())
                  }}
                  className="text-xs text-text-secondary hover:text-text transition-colors px-3 py-1.5 rounded-lg border border-border hover:border-gold/20"
                >
                  {selectMode ? '取消选择' : '管理'}
                </button>
                {selectMode && (
                  <button
                    onClick={deleteSelected}
                    disabled={deleting || selectedIds.size === 0}
                    className="text-xs text-red-400 hover:text-red-300 transition-colors px-3 py-1.5 rounded-lg border border-red-500/20 hover:border-red-500/40 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {deleting ? '删除中...' : `删除 (${selectedIds.size})`}
                  </button>
                )}
              </div>
            )}
            <div className="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-5 gap-3">
              {items.map((item: any, i: number) => (
                <div key={item.cmsVideoId || item.videoId || i}
                  onClick={() => {
                    if (tab === 'history' && selectMode) {
                      setSelectedIds(prev => {
                        const next = new Set(prev)
                        if (next.has(item.historyId)) next.delete(item.historyId)
                        else next.add(item.historyId)
                        return next
                      })
                      return
                    }
                    const srcParam = item.source ? `?source=${encodeURIComponent(item.source)}` : '';
                    navigate(`/detail/${item.cmsVideoId || item.videoId}${srcParam}`);
                  }}
                  className={`group cursor-pointer rounded-lg overflow-hidden bg-surface border transition-colors
                    card-shadow ${selectMode && tab === 'history' && selectedIds.has(item.historyId) ? 'border-gold/50' : 'border-border'}`}>
                  <div className="aspect-[3/4] relative overflow-hidden bg-surface-2">
                    {item.coverUrl ? (
                      <img src={item.coverUrl}
                        alt={item.title}
                        className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
                        onError={(e) => { (e.target as HTMLImageElement).style.display = 'none' }}
                      />
                    ) : (
                      <div className="w-full h-full flex items-center justify-center text-2xl">🎬</div>
                    )}
                    {tab === 'history' && item.progress > 30 && (
                      <div className="absolute bottom-0 left-0 right-0 h-1 bg-black/40">
                        <div className="h-full bg-gold/60 rounded-r-full"
                          style={{ width: `${Math.min(100, (item.progress / (item.duration || 3600)) * 100)}%` }} />
                      </div>
                    )}
                    {tab === 'history' && (
                      <>
                        {selectMode && (
                          <div className="absolute top-2 left-2 z-10">
                            <div className={`w-5 h-5 rounded-full border-2 flex items-center justify-center transition-colors ${
                              selectedIds.has(item.historyId) ? 'bg-gold border-gold' : 'bg-black/40 border-white/60'
                            }`}>
                              {selectedIds.has(item.historyId) && <span className="text-[10px] text-black font-bold">✓</span>}
                            </div>
                          </div>
                        )}
                        <button
                          onClick={(e) => deleteSingle(item.historyId, e)}
                          disabled={deleting}
                          className="absolute top-2 right-2 z-10 w-6 h-6 rounded-full bg-black/50 flex items-center justify-center 
                            opacity-0 group-hover:opacity-100 transition-opacity hover:bg-red-500/80 disabled:opacity-50"
                        >
                          <span className="text-white text-xs">✕</span>
                        </button>
                      </>
                    )}
                  </div>
                  <div className="p-2">
                    <p className="text-xs text-text truncate">{item.title}</p>
                    {tab === 'history' && item.progress > 0 && (
                      <p className="text-[10px] text-text-secondary mt-0.5">
                        {Math.floor(item.progress / 60)}:{(item.progress % 60).toString().padStart(2, '0')}
                      </p>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </>
        )}
      </div>
    </div>
  )
}
