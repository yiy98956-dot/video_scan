import { useState, useEffect } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import type { VideoDetailVO } from '../lib/types'
import { videoApi, coverUrl } from '../lib/api'
import LoadingSkeleton from '../components/LoadingSkeleton'
import ErrorState from '../components/ErrorState'
import InteractionBar from '../components/InteractionBar'
import CommentSection from '../components/CommentSection'

const CACHE_KEY = 'detail_cache_'

function getCache(id: string): VideoDetailVO | null {
  try {
    const raw = sessionStorage.getItem(CACHE_KEY + id)
    return raw ? JSON.parse(raw) : null
  } catch { return null }
}
function setCache(id: string, data: VideoDetailVO) {
  try { sessionStorage.setItem(CACHE_KEY + id, JSON.stringify(data)) } catch {}
}

const catColors: Record<string, string> = {
  '电影': 'bg-amber-900/30 text-amber-300 border-amber-700/30',
  '电视剧': 'bg-sky-900/30 text-sky-300 border-sky-700/30',
  '短剧': 'bg-rose-900/30 text-rose-300 border-rose-700/30',
  '动漫': 'bg-violet-900/30 text-violet-300 border-violet-700/30',
  '综艺': 'bg-emerald-900/30 text-emerald-300 border-emerald-700/30',
  '纪录片': 'bg-orange-900/30 text-orange-300 border-orange-700/30',
}
function catStyle(t: string): string {
  return catColors[t] || 'bg-zinc-800/50 text-zinc-300 border-zinc-700/30'
}

export default function Detail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  // 从 URL 查询参数读取 source，确保获取正确源的数据
  const sourceParam = new URLSearchParams(window.location.search).get('source') || undefined
  const [movie, setMovie] = useState<VideoDetailVO | null>(() => id ? getCache(id) : null)
  const [loading, setLoading] = useState(!movie)
  const [error, setError] = useState<string | null>(null)
  const [activeSource, setActiveSource] = useState(0)

  const loadDetail = async () => {
    if (!id) return
    setLoading(!getCache(id))
    setError(null)
    try {
      // 传递 source 参数给 API，避免跨源匹配导致数据不一致
      const data = await videoApi.detail(Number(id), sourceParam)
      setMovie(data)
      setCache(id, data)
    } catch (err) {
      if (!movie) setError(err instanceof Error ? err.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { loadDetail() }, [id, sourceParam])

  const currentSource = movie?.plays?.[activeSource]
  const totalEps = movie?.plays?.reduce((s, p) => s + (p.urls?.length || 0), 0) || 0

  if (loading && !movie) return <LoadingSkeleton variant="detail" />
  if (error && !movie) return <ErrorState message={error} onRetry={loadDetail} />
  if (!movie) return <ErrorState message="未找到影视信息" onRetry={() => navigate('/')} />

  const handlePlay = () => {
    const srcParam = sourceParam ? `?source=${encodeURIComponent(sourceParam)}` : '';
    navigate(`/play/${id}${srcParam}`)
  }

  return (
    <div className="min-h-screen pb-20">
      {/* Backdrop */}
      <div className="relative h-[36vh] sm:h-[42vh] overflow-hidden">
        {movie.coverUrl && (
          <>
            <img src={coverUrl(movie.coverUrl)} alt="" className="absolute inset-0 w-full h-full object-cover blur-md opacity-25"
              onError={(e) => { (e.target as HTMLImageElement).style.display = 'none' }} />
            <div className="absolute inset-0 bg-gradient-to-t from-bg via-bg/70 to-bg/40" />
          </>
        )}
        {/* 返回按钮 — 定位在 TopNav 下方，增大点击区域 */}
        <button
          type="button"
          onClick={(e) => {
            e.preventDefault()
            e.stopPropagation()
            console.log('[Detail] Back button clicked')
            navigate(-1)
          }}
          className="absolute top-20 md:top-20 left-4 z-[100] w-11 h-11 rounded-full bg-black/60 backdrop-blur-md border border-white/20
            flex items-center justify-center text-white hover:text-white hover:bg-black/80 active:scale-95 transition-all duration-200 shadow-xl cursor-pointer">
          <svg className="w-5 h-5 pointer-events-none" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
          </svg>
        </button>
      </div>

      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 -mt-28 relative z-10">
        <div className="flex flex-col md:flex-row gap-8">
          {/* 封面 */}
          <div className="w-40 sm:w-48 flex-shrink-0 mx-auto md:mx-0 animate-fade-up">
            <div className="aspect-[3/4] rounded-xl overflow-hidden border border-border shadow-[0_8px_32px_rgba(0,0,0,0.5)]">
              {movie.coverUrl ? (
                <img src={coverUrl(movie.coverUrl)} alt={movie.title}
                  className="w-full h-full object-cover"
                  onError={(e) => { (e.target as HTMLImageElement).style.display = 'none' }} />
              ) : (
                <div className="w-full h-full flex items-center justify-center bg-surface-2 text-3xl">🎬</div>
              )}
            </div>
          </div>

          {/* 信息 */}
          <div className="flex-1 pt-2 animate-fade-up" style={{ animationDelay: '0.1s' }}>
            <h1 className="text-2xl sm:text-3xl md:text-4xl text-text font-bold mb-4 leading-tight">{movie.title}</h1>

            <div className="flex flex-wrap items-center gap-2 mb-4">
              {movie.type && (
                <span className={`px-2.5 py-0.5 text-xs rounded-md border ${catStyle(movie.type)}`}>{movie.type}</span>
              )}
              {movie.score && Number(movie.score) > 0 && (
                <span className="font-serif-en text-gold text-sm">★ {movie.score}</span>
              )}
              {movie.year > 0 && <span className="text-xs text-text-secondary">{movie.year}</span>}
              {movie.area && <span className="text-xs text-text-secondary">/ {movie.area}</span>}
              {totalEps > 0 && (
                <span className="px-2 py-0.5 text-xs rounded-md border border-border text-text-secondary">{totalEps} 集</span>
              )}
              {movie.remark && (
                <span className="px-2 py-0.5 text-xs rounded-md bg-surface-2 text-text-secondary">{movie.remark}</span>
              )}
            </div>

            {/* 简介 */}
            {movie.description && (
              <p className="text-text-secondary text-sm leading-relaxed mb-5 max-w-2xl line-clamp-3">{movie.description}</p>
            )}

            {/* 导演/主演 */}
            <div className="space-y-1 mb-5">
              {movie.director && (
                <p className="text-sm"><span className="text-text-secondary">导演 </span><span className="text-text">{movie.director}</span></p>
              )}
              {movie.actors && (
                <p className="text-sm"><span className="text-text-secondary">主演 </span><span className="text-text">{movie.actors}</span></p>
              )}
            </div>

            {/* 播放按钮 */}
            {currentSource?.urls?.length ? (
              <div className="flex items-center gap-3">
                <button onClick={handlePlay}
                  className="px-8 py-2.5 rounded-lg bg-gold/10 text-gold border border-gold/30
                    hover:bg-gold/15 transition-all duration-300 text-sm font-medium">
                  ▶ 立即播放
                </button>
                {movie.progress && movie.progress > 30 && (
                  <span className="text-xs text-text-secondary">
                    上次看到 {Math.floor(movie.progress / 60)}:{String(movie.progress % 60).padStart(2, '0')}
                  </span>
                )}
              </div>
            ) : null}

            {/* 互动栏 */}
            <div className="mt-5">
              <InteractionBar
                videoId={movie.cmsVideoId}
                source={sourceParam}
                initialLiked={movie.liked}
                initialFavorited={movie.favorited}
                likeCount={movie.likeCount}
                collectCount={movie.collectCount}
                commentCount={movie.commentCount}
              />
            </div>
          </div>
        </div>

        {/* 分割线 */}
        <div className="gold-divider my-10" />

        {/* 播放源 + 剧集 */}
        {movie.plays && movie.plays.length > 0 && (
          <div className="animate-fade-up" style={{ animationDelay: '0.2s' }}>
            {/* 源切换 */}
            <div className="flex flex-wrap gap-2 mb-6">
              {movie.plays.map((source, index) => (
                <button key={index} onClick={() => setActiveSource(index)}
                  className={`px-4 py-1.5 rounded-full text-xs transition-all duration-300 ${
                    index === activeSource
                      ? 'bg-gold/10 text-gold border border-gold/25'
                      : 'bg-surface border border-border text-text-secondary hover:border-gold/15 hover:text-text'
                  }`}>
                  {source.name || source.from}
                </button>
              ))}
            </div>

            {currentSource?.urls?.length ? (
              <>
                <h3 className="text-sm text-text mb-4">{currentSource.name || currentSource.from} · {currentSource.urls.length} 集</h3>
                <div className="grid grid-cols-4 sm:grid-cols-6 md:grid-cols-8 lg:grid-cols-10 xl:grid-cols-12 gap-2">
                  {currentSource.urls.map((ep, i) => (
                    <button key={i} onClick={handlePlay}
                      className="flex items-center justify-center px-2 py-2 rounded-lg text-xs bg-surface border border-border
                        text-text-secondary hover:border-gold/20 hover:text-text hover:bg-surface-2 transition-all duration-200">
                      {ep.episode || `第${i + 1}集`}
                    </button>
                  ))}
                </div>
              </>
            ) : (
              <p className="text-text-secondary text-sm text-center py-12">该播放源暂无剧集</p>
            )}
          </div>
        )}

        {(!movie.plays || movie.plays.length === 0) && (
            <div className="flex flex-col items-center justify-center py-16">
              <p className="text-text-secondary text-sm mb-2">暂无播放资源</p>
            </div>
          )}

          {/* 评论区 */}
          <CommentSection videoId={movie.cmsVideoId} />
        </div>
      </div>
  )
}
