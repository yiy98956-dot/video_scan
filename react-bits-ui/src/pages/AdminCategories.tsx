import { useState, useEffect } from 'react'
import { useAuth } from '../lib/auth'
import { useNavigate } from 'react-router-dom'

const BASE = '/api'

interface CategoryItem {
  id: number; name: string; alias?: string; count?: number; is_show?: boolean; subs?: CategorySub[]
}
interface CategorySub {
  id: number; name: string; count?: number; is_show?: boolean
}

export default function AdminCategories() {
  const { user, token } = useAuth()
  const navigate = useNavigate()
  const [cats, setCats] = useState<CategoryItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [toggling, setToggling] = useState<number | null>(null)

  const fetchCats = async () => {
    if (!token) return
    setLoading(true)
    setError('')
    try {
      const res = await fetch(`${BASE}/category/tree-with-counts?showHidden=1`, {
        headers: { 'Authorization': `Bearer ${token}` },
      })
      const text = await res.text()
      if (!text) return
      const json = JSON.parse(text)
      if (json.code === 200) {
        const items = json.data?.items || []
        setCats(items)
      }
    } catch (e) {
      setError('加载分类失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (!user) navigate('/auth')
    if (user?.role !== 'admin') navigate('/')
    if (token) fetchCats()
  }, [user, token])

  const toggle = async (id: number) => {
    setToggling(id)
    try {
      const res = await fetch(`${BASE}/category/toggle?id=${id}`, {
        headers: { 'Authorization': `Bearer ${token}` },
      })
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`)
      }
      const json = await res.json()
      if (json.code !== 200) {
        setError(json.message || '操作失败')
        return
      }
      // 操作成功后再刷新列表
      await fetchCats()
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败')
    } finally {
      setToggling(null)
    }
  }

  if (!user) return null
  if (user.role !== 'admin') return null

  return (
    <div className="min-h-screen pt-24 px-4 sm:px-6 lg:px-8">
      <div className="max-w-4xl mx-auto">
        <div className="mb-8 animate-fade-up">
          <h1 className="text-2xl text-text font-medium">分类管理</h1>
          <p className="text-text-secondary text-sm mt-1.5">控制各分类及子分类的可见性</p>
        </div>

        {loading ? (
          <div className="space-y-4">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="h-16 rounded-lg bg-surface-2 animate-shimmer-gold" />
            ))}
          </div>
        ) : error ? (
          <div className="text-center py-12">
            <p className="text-text-secondary text-sm mb-4">{error}</p>
            <button onClick={fetchCats}
              className="text-xs text-gold hover:text-gold-light transition-colors">重试</button>
          </div>
        ) : (
          <div className="space-y-3 animate-fade-up">
            {cats.map((cat) => (
              <div key={cat.id}
                className="bg-surface border border-border rounded-xl overflow-hidden card-shadow">
                {/* 一级分类 */}
                <div className="flex items-center justify-between px-5 py-3.5">
                  <div className="flex items-center gap-3">
                    <span className="text-sm text-text font-medium">{cat.name}</span>
                    {cat.alias && (
                      <span className="text-[10px] text-text-secondary bg-surface-2 px-1.5 py-0.5 rounded">{cat.alias}</span>
                    )}
                    {cat.count != null && (
                      <span className="text-[10px] text-text-secondary/60">{cat.count} 部</span>
                    )}
                  </div>
                  <button onClick={() => toggle(cat.id)} disabled={toggling === cat.id}
                    className={`px-3 py-1 rounded text-xs transition-all duration-200 ${
                      toggling === cat.id ? 'opacity-50' :
                      cat.is_show === false
                        ? 'bg-rose-900/20 text-rose-300 border border-rose-700/30 hover:bg-rose-900/30'
                        : 'bg-emerald-900/20 text-emerald-300 border border-emerald-700/30 hover:bg-emerald-900/30'
                    }`}>
                    {cat.is_show === false ? '隐藏' : '显示'}
                  </button>
                </div>

                {/* 子分类 */}
                {cat.subs && cat.subs.length > 0 && (
                  <div className="px-5 pb-3.5 flex flex-wrap gap-1.5 border-t border-border pt-3 mt-0">
                    {cat.subs.map((sub) => (
                      <div key={sub.id}
                        className="flex items-center gap-1.5 px-2.5 py-1 rounded text-[11px] bg-surface-2">
                        <span className={sub.is_show === false ? 'text-rose-400/60' : 'text-text-secondary'}>{sub.name}</span>
                        <button onClick={() => toggle(sub.id)} disabled={toggling === sub.id}
                          className={`ml-0.5 text-[10px] transition-colors ${
                            sub.is_show === false ? 'text-rose-400/50 hover:text-rose-300' : 'text-emerald-400/50 hover:text-emerald-300'
                          }`}>
                          {sub.is_show === false ? '◉' : '◎'}
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
