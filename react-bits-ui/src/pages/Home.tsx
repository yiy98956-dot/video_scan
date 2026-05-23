import { useState, useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import type { VideoItemVO } from '../lib/types'
import { videoApi } from '../lib/api'
import MovieCard from '../components/MovieCard'
import AutoScrollCarousel from '../components/AutoScrollCarousel'
import LoadingSkeleton from '../components/LoadingSkeleton'
import ErrorState from '../components/ErrorState'
import EmptyState from '../components/EmptyState'

const typeInfo: { key: string; emoji: string; label: string; color: string }[] = [
  { key: '电影', emoji: '🎬', label: '电影', color: 'from-amber-900/20 to-transparent' },
  { key: '电视剧', emoji: '📺', label: '电视剧', color: 'from-sky-900/20 to-transparent' },
  { key: '短剧', emoji: '🎭', label: '短剧', color: 'from-rose-900/20 to-transparent' },
  { key: '动漫', emoji: '🎨', label: '动漫', color: 'from-violet-900/20 to-transparent' },
  { key: '综艺', emoji: '🎤', label: '综艺', color: 'from-emerald-900/20 to-transparent' },
  { key: '纪录片', emoji: '📽️', label: '纪录片', color: 'from-orange-900/20 to-transparent' },
]

function TypeCard({ type, onClick }: { type: typeof typeInfo[0]; onClick: () => void }) {
  return (
    <button onClick={onClick}
      className="group relative flex flex-col items-center justify-center p-5 rounded-xl bg-surface border border-border
        hover:border-gold/20 hover:bg-surface-2 transition-all duration-400 overflow-hidden">
      {/* 悬停底色 */}
      <div className={`absolute inset-0 bg-gradient-to-br ${type.color} opacity-0 group-hover:opacity-100 transition-opacity duration-400`} />
      <span className="relative text-2xl mb-2 group-hover:scale-110 transition-transform duration-400">{type.emoji}</span>
      <span className="relative text-xs text-text-secondary group-hover:text-text transition-colors duration-300">{type.label}</span>
    </button>
  )
}

function SectionTitle({ children, accent }: { children: React.ReactNode; accent?: string }) {
  const fromColor = accent || 'from-gold/20'
  return (
    <div className="flex items-center gap-3 mb-6">
      <h2 className="text-lg text-text font-medium">{children}</h2>
      <div className={`flex-1 h-px bg-gradient-to-r ${fromColor} to-transparent`} />
    </div>
  )
}

export default function Home() {
  const navigate = useNavigate()
  const [hotMovies, setHotMovies] = useState<VideoItemVO[]>([])
  const [latestMovies, setLatestMovies] = useState<VideoItemVO[]>([])
  const [hotLoading, setHotLoading] = useState(true)
  const [latestLoading, setLatestLoading] = useState(true)
  const [hotError, setHotError] = useState<string | null>(null)
  const [latestError, setLatestError] = useState<string | null>(null)

  const loadHot = async () => {
    setHotLoading(true)
    setHotError(null)
    try {
      const res = await videoApi.list({ page: 1, size: 12, sort: 'score' })
      setHotMovies(res.items || [])
    } catch (err) {
      setHotError(err instanceof Error ? err.message : '加载失败')
    } finally { setHotLoading(false) }
  }

  const loadLatest = async () => {
    setLatestLoading(true)
    setLatestError(null)
    try {
      const res = await videoApi.list({ page: 1, size: 18, sort: 'time' })
      setLatestMovies(res.items || [])
    } catch (err) {
      setLatestError(err instanceof Error ? err.message : '加载失败')
    } finally { setLatestLoading(false) }
  }

  useEffect(() => { loadHot(); loadLatest() }, [])

  return (
    <div className="min-h-screen">
      {/* Hero */}
      <section className="relative pt-28 pb-16 px-4 sm:px-6 lg:px-8 overflow-hidden">
        {/* 背景装饰 */}
        <div className="hero-glow" />
        <div className="absolute inset-0" style={{
          backgroundImage: 'radial-gradient(rgba(201,169,110,0.03) 1px, transparent 1px)',
          backgroundSize: '40px 40px'
        }} />
        <div className="absolute top-20 left-10 w-32 h-32 rounded-full bg-amber-900/10 blur-[60px] pointer-events-none" />
        <div className="absolute top-40 right-10 w-40 h-40 rounded-full bg-sky-900/10 blur-[60px] pointer-events-none" />

        <div className="max-w-4xl mx-auto text-center relative">
          <div className="relative inline-block mb-6">
            <h1 className="text-5xl sm:text-6xl md:text-7xl font-serif-en italic font-bold text-gold animate-fade-up">
              影视星河
            </h1>
            <div className="absolute -bottom-2 left-0 right-0 h-px bg-gradient-to-r from-transparent via-gold/40 to-transparent" />
          </div>
          <p className="text-text-secondary text-lg sm:text-xl max-w-lg mx-auto animate-fade-up" style={{ animationDelay: '0.15s' }}>
            每一帧都值得被看见
          </p>
          <div className="flex items-center justify-center gap-4 mt-8 animate-fade-up" style={{ animationDelay: '0.3s' }}>
            <button onClick={() => navigate('/browse')}
              className="px-7 py-2.5 rounded-lg bg-gold/8 text-gold border border-gold/25 text-sm
                hover:bg-gold/12 transition-all duration-300">
              开始浏览
            </button>
            <button onClick={() => navigate('/search')}
              className="px-7 py-2.5 rounded-lg bg-surface text-text-secondary border border-border text-sm
                hover:border-gold/15 hover:text-text transition-all duration-300">
              搜索资源
            </button>
          </div>
        </div>
      </section>

      {/* Category Grid */}
      <section className="px-4 sm:px-6 lg:px-8 mb-14 animate-fade-up" style={{ animationDelay: '0.2s' }}>
        <div className="max-w-6xl mx-auto">
          <SectionTitle accent="from-amber-900/40">分类入口</SectionTitle>
          <div className="grid grid-cols-3 sm:grid-cols-6 gap-3">
            {typeInfo.map((t) => (
              <TypeCard key={t.key} type={t} onClick={() => navigate(`/browse?type=${t.key}`)} />
            ))}
          </div>
        </div>
      </section>

      {/* Hot */}
      <section className="px-4 sm:px-6 lg:px-8 mb-14">
        <div className="max-w-6xl mx-auto">
          <SectionTitle accent="from-amber-800/40">🔥 热门推荐</SectionTitle>
          {hotLoading ? (
            <LoadingSkeleton variant="card" count={6} />
          ) : hotError ? (
            <ErrorState message={hotError} onRetry={loadHot} />
          ) : hotMovies.length === 0 ? (
            <EmptyState message="暂无热门推荐" />
          ) : (
            <AutoScrollCarousel videos={hotMovies} speed={0.8} />
          )}
        </div>
      </section>

      {/* Latest */}
      <section className="px-4 sm:px-6 lg:px-8">
        <div className="max-w-6xl mx-auto">
          <SectionTitle accent="from-sky-800/40">✨ 最近更新</SectionTitle>
          {latestLoading ? (
            <LoadingSkeleton variant="card" count={12} />
          ) : latestError ? (
            <ErrorState message={latestError} onRetry={loadLatest} />
          ) : latestMovies.length === 0 ? (
            <EmptyState message="暂无更新" />
          ) : (
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-4">
              {latestMovies.map((video) => (
                <MovieCard key={video.cmsVideoId} video={video} />
              ))}
            </div>
          )}
        </div>
      </section>
    </div>
  )
}
