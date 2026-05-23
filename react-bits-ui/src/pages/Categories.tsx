import { useState, useEffect, useCallback } from 'react'
import { useLocation } from 'react-router-dom'
import type { VideoItemVO, CategoryNode } from '../lib/types'
import { videoApi, categoryApi } from '../lib/api'
import MovieCard from '../components/MovieCard'
import LoadingSkeleton from '../components/LoadingSkeleton'
import ErrorState from '../components/ErrorState'
import EmptyState from '../components/EmptyState'
import Pagination from '../components/Pagination'

/** 从当前 URL 读取 search 参数（同步，无延迟） */
function readUrlParams() {
  const sp = new URLSearchParams(window.location.search)
  return {
    cat: sp.get('cat') || '',
    sub: sp.get('sub') || '',
    page: Number(sp.get('page')) || 1,
  }
}

/** 同步更新 URL（不触发 React Router 重渲染） */
function updateUrl(params: { cat?: string; sub?: string; page?: number }) {
  const sp = new URLSearchParams(window.location.search)
  if (params.cat !== undefined) {
    if (params.cat) sp.set('cat', params.cat)
    else sp.delete('cat')
  }
  if (params.sub !== undefined) {
    if (params.sub) sp.set('sub', params.sub)
    else sp.delete('sub')
  }
  if (params.page !== undefined) sp.set('page', String(params.page))
  const qs = sp.toString()
  const newUrl = window.location.pathname + (qs ? '?' + qs : '')
  window.history.replaceState(null, '', newUrl)
}

export default function Categories() {
  const location = useLocation()
  const [categories, setCategories] = useState<CategoryNode[]>([])
  const [activeCat, setActiveCat] = useState(() => readUrlParams().cat)
  const [activeSub, setActiveSub] = useState(() => readUrlParams().sub)
  const [currentPage, setCurrentPage] = useState(() => readUrlParams().page)
  const [videos, setVideos] = useState<VideoItemVO[]>([])
  const [total, setTotal] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [loadingCats, setLoadingCats] = useState(true)
  const [loadingVideos, setLoadingVideos] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const pageSize = 24

  // 浏览器前进/后退时同步状态
  useEffect(() => {
    const { cat, sub, page } = readUrlParams()
    setActiveCat(cat)
    setActiveSub(sub)
    setCurrentPage(page)
  }, [location.key])

  const loadVideos = useCallback(async (cat: string, sub: string, page: number) => {
    if (!cat) return
    setLoadingVideos(true)
    setError(null)
    try {
      const res = await videoApi.list({
        page,
        size: pageSize,
        sort: 'score',
        type: cat,
        ...(sub ? { category: sub } : {}),
      })
      setVideos(res.items || [])
      setTotal(res.total || 0)
      setTotalPages(res.totalPages || 0)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
      setVideos([])
    } finally {
      setLoadingVideos(false)
    }
  }, [])

  // 状态变化时加载视频
  useEffect(() => {
    if (activeCat) {
      loadVideos(activeCat, activeSub, currentPage)
    }
  }, [activeCat, activeSub, currentPage, loadVideos])

  // 加载分类列表
  useEffect(() => {
    let cancelled = false
    const init = async () => {
      setLoadingCats(true)
      try {
        const data = await categoryApi.tree()
        if (cancelled) return
        const cats = Array.isArray(data) ? data : []
        setCategories(cats)

        // 只有 URL 没有 cat 参数时，才默认选第一个分类
        const { cat } = readUrlParams()
        if (!cat && cats.length > 0) {
          const first = cats.find(c => c.count > 0)
          if (first) {
            setActiveCat(first.name)
            updateUrl({ cat: first.name, page: 1 })
          }
        }
      } catch {
        if (cancelled) return
        setCategories(['电影', '电视剧', '短剧', '动漫', '综艺', '纪录片'].map((name, i) => ({
          id: i + 1, name, count: 0,
        })))
      } finally {
        if (!cancelled) setLoadingCats(false)
      }
    }
    init()
    return () => { cancelled = true }
  }, [])

  // 切换一级分类
  const onCatClick = (catName: string) => {
    setActiveCat(catName)
    setActiveSub('')
    setCurrentPage(1)
    updateUrl({ cat: catName, sub: '', page: 1 })
  }

  // 切换二级子分类
  const onSubClick = (subName: string) => {
    const newSub = subName === activeSub ? '' : subName
    setActiveSub(newSub)
    setCurrentPage(1)
    updateUrl({ sub: newSub, page: 1 })
  }

  // 翻页
  const onPageChange = (page: number) => {
    setCurrentPage(page)
    updateUrl({ page })
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  return (
    <div className="min-h-screen pt-24 px-4 sm:px-6 lg:px-8">
      <div className="max-w-6xl mx-auto">
        <div className="mb-8 animate-fade-up">
          <h1 className="text-2xl text-text font-medium">分类浏览</h1>
          <p className="text-text-secondary text-sm mt-1.5">按类别发现精彩内容</p>
        </div>

        {/* 一级分类 Tabs */}
        {loadingCats ? (
          <div className="flex gap-2 mb-8">
            {Array.from({ length: 6 }).map((_, i) => (
              <div key={i} className="h-9 w-16 rounded-full bg-surface border border-border animate-shimmer-gold" />
            ))}
          </div>
        ) : (
          <div className="flex flex-wrap gap-2 mb-2 animate-fade-up" style={{ animationDelay: '0.1s' }}>
            {categories.map((cat) => (
              <button key={cat.id}
                onClick={() => onCatClick(cat.name)}
                className={`px-5 py-2 rounded-full text-xs transition-all duration-300 ${
                  activeCat === cat.name
                    ? 'bg-gold/10 text-gold border border-gold/25'
                    : 'bg-surface border border-border text-text-secondary hover:border-gold/15 hover:text-text'
                }`}>
                {cat.name}
              </button>
            ))}
          </div>
        )}

        {/* 二级子分类 */}
        {activeCat && (() => {
          const cur = categories.find(c => c.name === activeCat)
          const subs = (cur as any)?.subs
          if (!subs?.length) return null
          return (
            <div className="flex flex-wrap gap-1.5 mb-8 animate-fade-up pl-1" style={{ animationDelay: '0.15s' }}>
              {subs.map((child: any) => (
                <button key={child.id}
                  onClick={() => onSubClick(child.name)}
                  className={`px-2.5 py-1 text-[11px] rounded transition-all duration-200 ${
                    activeSub === child.name
                      ? 'bg-gold/15 text-gold border border-gold/20'
                      : 'bg-surface-2 text-text-secondary hover:bg-surface hover:text-text'
                  }`}>
                  {child.name}
                </button>
              ))}
            </div>
          )
        })()}

        {/* 分割线 */}
        <div className="gold-divider mb-8" />

        {/* 视频网格 */}
        {activeCat && (
          <>
            {!loadingVideos && !error && videos.length > 0 && (
              <div className="text-xs text-text-secondary mb-4">
                共 {total} 部作品
              </div>
            )}

            {loadingVideos ? (
              <LoadingSkeleton variant="card" count={pageSize} />
            ) : error ? (
              <ErrorState message={error} onRetry={() => loadVideos(activeCat, activeSub, currentPage)} />
            ) : videos.length === 0 ? (
              <EmptyState message={`「${activeCat}${activeSub ? ` · ${activeSub}` : ''}」暂无数据`} />
            ) : (
              <>
                <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-4 animate-fade-up">
                  {videos.map((video) => (
                    <MovieCard key={video.cmsVideoId} video={video} />
                  ))}
                </div>

                <Pagination
                  current={currentPage}
                  total={totalPages}
                  onChange={onPageChange}
                />
              </>
            )}
          </>
        )}
      </div>
    </div>
  )
}
