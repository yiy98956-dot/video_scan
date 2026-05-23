import { useState, useEffect, useCallback, useRef } from 'react'
import { useLocation } from 'react-router-dom'
import type { VideoItemVO } from '../lib/types'
import { videoApi } from '../lib/api'
import MovieCard from '../components/MovieCard'
import LoadingSkeleton from '../components/LoadingSkeleton'
import ErrorState from '../components/ErrorState'
import EmptyState from '../components/EmptyState'
import Pagination from '../components/Pagination'

const sortOptions = [
  { value: 'time', label: '时间排序' },
  { value: 'score', label: '评分排序' },
  { value: 'hot', label: '热门排序' },
]

/** 从当前 URL 读取 search 参数（同步，无延迟） */
function readUrlParams() {
  const sp = new URLSearchParams(window.location.search)
  return {
    page: Number(sp.get('page')) || 1,
    sort: sp.get('sort') || 'time',
    type: sp.get('type') || '',
    category: sp.get('category') || '',
  }
}

/** 同步更新 URL */
function updateUrl(params: Record<string, string>) {
  const sp = new URLSearchParams(window.location.search)
  for (const [key, value] of Object.entries(params)) {
    if (value) sp.set(key, value)
    else sp.delete(key)
  }
  if (!params.page) sp.set('page', '1')
  const qs = sp.toString()
  window.history.replaceState(null, '', window.location.pathname + (qs ? '?' + qs : ''))
}

export default function Browse() {
  const contentRef = useRef<HTMLDivElement>(null)
  const location = useLocation()

  // 从 URL 初始化状态（同步，无延迟）
  const [currentPage, setCurrentPage] = useState(() => readUrlParams().page)
  const [currentSort, setCurrentSort] = useState(() => readUrlParams().sort)
  const [currentType, setCurrentType] = useState(() => readUrlParams().type)
  const [currentCategory, setCurrentCategory] = useState(() => readUrlParams().category)
  const [movies, setMovies] = useState<VideoItemVO[]>([])
  const [types, setTypes] = useState<string[]>([])
  const [total, setTotal] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const pageSize = 24

  // 浏览器前进/后退时同步状态
  useEffect(() => {
    const { page, sort, type, category } = readUrlParams()
    setCurrentPage(page)
    setCurrentSort(sort)
    setCurrentType(type)
    setCurrentCategory(category)
  }, [location.key])

  const loadMovies = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await videoApi.list({
        page: currentPage,
        size: pageSize,
        sort: currentSort,
        type: currentType || undefined,
        category: currentCategory || undefined,
      })
      setMovies(res.items || [])
      setTotal(res.total || 0)
      setTotalPages(res.totalPages || 0)
      if (currentPage > 1 && contentRef.current) {
        contentRef.current.scrollIntoView({ behavior: 'smooth', block: 'start' })
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
      setMovies([])
    } finally {
      setLoading(false)
    }
  }, [currentPage, currentSort, currentType, currentCategory])

  useEffect(() => { loadMovies() }, [loadMovies])

  const loadTypes = async () => {
    try {
      const data = await videoApi.types()
      setTypes(data)
    } catch {
      setTypes(['电影', '电视剧', '短剧', '动漫', '综艺', '纪录片'])
    }
  }

  useEffect(() => { loadTypes() }, [])

  const updateParams = (params: Record<string, string>) => {
    if (params.sort) setCurrentSort(params.sort)
    if (params.type !== undefined) setCurrentType(params.type)
    if (params.category !== undefined) setCurrentCategory(params.category)
    if (params.page) setCurrentPage(Number(params.page))
    else setCurrentPage(1)
    updateUrl(params)
  }

  return (
    <div className="min-h-screen pt-24 pb-20 px-4 sm:px-6 lg:px-8">
      <div className="max-w-6xl mx-auto">
        {/* Header */}
        <div className="mb-8 animate-fade-up">
          <h1 className="text-2xl text-text font-medium">浏览影视</h1>
          <p className="text-text-secondary text-sm mt-1.5">发现更多精彩内容</p>
        </div>

        {/* Filter Bar */}
        <div className="glass rounded-xl p-4 mb-8 animate-fade-up" style={{ animationDelay: '0.1s' }}>
          <div className="flex flex-wrap items-center gap-4">
            <div className="flex items-center gap-2">
              <label className="text-xs text-text-secondary whitespace-nowrap">类型:</label>
              <select
                value={currentType}
                onChange={(e) => updateParams({ type: e.target.value, page: '1' })}
                className="bg-surface border border-border rounded-lg px-3 py-1.5 text-xs text-text
                  focus:outline-none focus:border-gold/30 transition-all duration-200"
              >
                <option value="">全部</option>
                {types.map((t) => (
                  <option key={t} value={t}>{t}</option>
                ))}
              </select>
            </div>

            <div className="flex items-center gap-2">
              <label className="text-xs text-text-secondary whitespace-nowrap">排序:</label>
              <select
                value={currentSort}
                onChange={(e) => updateParams({ sort: e.target.value, page: '1' })}
                className="bg-surface border border-border rounded-lg px-3 py-1.5 text-xs text-text
                  focus:outline-none focus:border-gold/30 transition-all duration-200"
              >
                {sortOptions.map((opt) => (
                  <option key={opt.value} value={opt.value}>{opt.label}</option>
                ))}
              </select>
            </div>

            <div className="ml-auto text-xs text-text-secondary">
              找到 {total} 部作品
            </div>
          </div>
        </div>

        {/* Content */}
        <div ref={contentRef} className="scroll-mt-32">
          {loading ? (
            <LoadingSkeleton variant="card" count={pageSize} />
          ) : error ? (
            <ErrorState message={error} onRetry={loadMovies} />
          ) : movies.length === 0 ? (
            <EmptyState message="没有找到匹配的作品" hint="尝试调整筛选条件" />
          ) : (
            <>
              <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-4">
                {movies.map((video) => (
                  <MovieCard key={video.cmsVideoId} video={video} />
                ))}
              </div>

              <Pagination
                current={currentPage}
                total={totalPages}
                onChange={(p) => updateParams({ page: String(p) })}
              />
            </>
          )}
        </div>
      </div>
    </div>
  )
}
