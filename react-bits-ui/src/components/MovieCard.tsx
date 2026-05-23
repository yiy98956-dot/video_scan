import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { VideoItemVO } from '../lib/types'
import { coverUrl } from '../lib/api'

interface Props {
  video: VideoItemVO
}

const typeColors: Record<string, string> = {
  '电影': 'bg-amber-900/40 text-amber-300 border-amber-700/30',
  '电视剧': 'bg-sky-900/40 text-sky-300 border-sky-700/30',
  '短剧': 'bg-rose-900/40 text-rose-300 border-rose-700/30',
  '动漫': 'bg-violet-900/40 text-violet-300 border-violet-700/30',
  '综艺': 'bg-emerald-900/40 text-emerald-300 border-emerald-700/30',
  '纪录片': 'bg-orange-900/40 text-orange-300 border-orange-700/30',
  '少儿': 'bg-pink-900/40 text-pink-300 border-pink-700/30',
  '体育': 'bg-blue-900/40 text-blue-300 border-blue-700/30',
  '资讯': 'bg-teal-900/40 text-teal-300 border-teal-700/30',
}
function tc(t: string): string { return typeColors[t] || 'bg-zinc-800/40 text-zinc-300 border-zinc-700/30' }

export default function MovieCard({ video }: Props) {
  const navigate = useNavigate()
  const [imgLoaded, setImgLoaded] = useState(false)
  const [imgError, setImgError] = useState(false)

  const score = parseFloat(video.score)
  const hasScore = !isNaN(score) && score > 0

  // 跳转详情时携带 source 参数，确保详情接口返回正确源的数据
  const handleNavigate = () => {
    const sourceParam = video.source ? `?source=${encodeURIComponent(video.source)}` : '';
    navigate(`/detail/${video.cmsVideoId}${sourceParam}`);
  };

  return (
    <div onClick={handleNavigate}
      className="group relative cursor-pointer rounded-lg overflow-hidden bg-surface border border-border
        card-shadow">
      {/* Cover */}
      <div className="aspect-[3/4] relative overflow-hidden bg-surface-2">
        {!imgLoaded && !imgError && (
          <div className="absolute inset-0 animate-shimmer-gold" />
        )}
        {imgError ? (
          <div className="w-full h-full flex flex-col items-center justify-center bg-gradient-to-br from-surface-2 to-surface gap-2">
            <svg className="w-8 h-8 text-text-secondary/30" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M2.25 15.75l5.159-5.159a2.25 2.25 0 013.182 0l5.159 5.159m-1.5-1.5l1.409-1.409a2.25 2.25 0 013.182 0l2.909 2.909M3.75 21h16.5A2.25 2.25 0 0022.5 18.75V5.25A2.25 2.25 0 0020.25 3H3.75A2.25 2.25 0 001.5 5.25v13.5A2.25 2.25 0 003.75 21z" />
            </svg>
            <span className="text-[10px] text-text-secondary/40">图片暂不可用</span>
          </div>
        ) : (
          <img src={coverUrl(video.coverUrl)} alt={video.title} loading="lazy"
            onLoad={() => setImgLoaded(true)} onError={() => setImgError(true)}
            className={`w-full h-full object-cover transition-all duration-500 group-hover:scale-105 ${imgLoaded ? 'opacity-100' : 'opacity-0'}`} />
        )}

        {/* 类型徽章 */}
        {video.type && (
          <span className={`absolute top-2 left-2 px-2 py-0.5 text-[10px] font-medium rounded border ${tc(video.type)}`}>
            {video.type}
          </span>
        )}

        {/* 评分 */}
        {hasScore && (
          <span className="absolute top-2 right-2 px-1.5 py-0.5 text-[10px] font-semibold rounded font-serif-en
            bg-black/50 backdrop-blur-sm border border-white/10 text-gold-light">
            {score.toFixed(1)}
          </span>
        )}

        {/* Hover overlay */}
        <div className="absolute inset-0 bg-gradient-to-t from-bg/80 via-transparent to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-400 flex flex-col justify-end p-3">
          {video.remark && (
            <span className="text-[11px] text-text-secondary">{video.remark}</span>
          )}
        </div>
      </div>

      {/* Info */}
      <div className="p-2.5 space-y-1">
        <h3 className="text-sm text-text leading-tight line-clamp-1 font-medium group-hover:text-gold transition-colors">{video.title}</h3>
        {(video.year > 0 || video.area) && (
          <div className="flex items-center gap-1.5 text-[11px] text-text-secondary">
            {video.year > 0 && <span>{video.year}</span>}
            {video.year > 0 && video.area && <span>·</span>}
            {video.area && <span className="truncate">{video.area}</span>}
          </div>
        )}
      </div>
    </div>
  )
}
