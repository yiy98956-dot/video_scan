import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import type { VideoItemVO } from '../lib/types'
import { coverUrl } from '../lib/api'

interface Props {
  videos: VideoItemVO[]
  speed?: number // 滚动速度，默认 1
}

export default function AutoScrollCarousel({ videos, speed = 1 }: Props) {
  const navigate = useNavigate()
  const containerRef = useRef<HTMLDivElement>(null)
  const [isPaused, setIsPaused] = useState(false)
  const [hoveredId, setHoveredId] = useState<number | null>(null)
  const scrollPos = useRef<number>(0)
  const animationRef = useRef<number | undefined>(undefined)

  useEffect(() => {
    const container = containerRef.current
    if (!container || videos.length === 0) return

    const scrollWidth = container.scrollWidth - container.clientWidth
    if (scrollWidth <= 0) return

    const animate = () => {
      if (!isPaused && container) {
        scrollPos.current += speed
        if (scrollPos.current > scrollWidth) {
          scrollPos.current = 0 // 循环回到开头
        }
        container.scrollLeft = scrollPos.current
      }
      animationRef.current = requestAnimationFrame(animate)
    }

    animationRef.current = requestAnimationFrame(animate)
    return () => {
      if (animationRef.current) cancelAnimationFrame(animationRef.current)
    }
  }, [isPaused, speed, videos.length])

  const handleMouseEnter = (id: number) => {
    setIsPaused(true)
    setHoveredId(id)
  }

  const handleMouseLeave = () => {
    setIsPaused(false)
    setHoveredId(null)
  }

  // 复制一份数据实现无缝循环
  const displayVideos = [...videos, ...videos]

  return (
    <div className="relative overflow-hidden">
      <div
        ref={containerRef}
        className="flex gap-4 overflow-x-hidden pb-4"
        style={{ scrollBehavior: 'auto' }}
      >
        {displayVideos.map((video, index) => (
          <div
            key={`${video.cmsVideoId}-${index}`}
            className={`flex-shrink-0 w-36 sm:w-40 cursor-pointer transition-all duration-300 ${
              hoveredId === video.cmsVideoId ? 'scale-110 z-10' : 'scale-100'
            }`}
            onMouseEnter={() => handleMouseEnter(video.cmsVideoId)}
            onMouseLeave={handleMouseLeave}
            onClick={() => navigate(`/detail/${video.cmsVideoId}?source=${encodeURIComponent(video.source || '')}`)}
          >
            {/* 封面 */}
            <div className="relative w-full rounded-lg overflow-hidden border border-border shadow-lg bg-surface"
                 style={{ aspectRatio: '3/4', minHeight: '180px' }}>
              {video.coverUrl ? (
                <>
                  <img
                    src={coverUrl(video.coverUrl)}
                    alt={video.title}
                    className="absolute inset-0 w-full h-full object-cover object-center"
                    loading="lazy"
                    onError={(e) => {
                      (e.target as HTMLImageElement).style.display = 'none'
                    }}
                  />
                  {/* 图片加载失败或异常时显示的备用标题 */}
                  <div className="absolute inset-0 flex items-center justify-center text-text-secondary text-xs p-2 text-center pointer-events-none"
                       style={{ zIndex: -1 }}>
                    {video.title}
                  </div>
                </>
              ) : (
                <div className="absolute inset-0 flex items-center justify-center text-text-secondary text-xs p-2 text-center">
                  {video.title}
                </div>
              )}
              {/* 悬停遮罩 */}
              <div className={`absolute inset-0 bg-gradient-to-t from-black/80 via-black/20 to-transparent transition-opacity duration-300 ${
                hoveredId === video.cmsVideoId ? 'opacity-100' : 'opacity-0'
              }`} />
              {/* 悬停时显示播放按钮 */}
              {hoveredId === video.cmsVideoId && (
                <div className="absolute inset-0 flex items-center justify-center">
                  <div className="w-12 h-12 rounded-full bg-gold/90 flex items-center justify-center shadow-lg animate-fade-in">
                    <svg className="w-5 h-5 text-black ml-0.5" fill="currentColor" viewBox="0 0 24 24">
                      <path d="M8 5v14l11-7z" />
                    </svg>
                  </div>
                </div>
              )}
              {/* 评分标签 */}
              {video.score && Number(video.score) > 0 && (
                <div className="absolute top-2 right-2 px-1.5 py-0.5 rounded bg-black/60 backdrop-blur-sm text-[10px] text-gold font-medium">
                  {Number(video.score).toFixed(1)}
                </div>
              )}
            </div>
            {/* 标题 */}
            <div className="mt-2 px-1">
              <h3 className="text-xs text-text truncate">{video.title}</h3>
              <p className="text-[10px] text-text-secondary truncate mt-0.5">
                {video.type} · {video.year || '未知年份'}
              </p>
            </div>
          </div>
        ))}
      </div>
      {/* 左右渐变遮罩 */}
      <div className="absolute left-0 top-0 bottom-4 w-8 bg-gradient-to-r from-bg to-transparent pointer-events-none z-10" />
      <div className="absolute right-0 top-0 bottom-4 w-8 bg-gradient-to-l from-bg to-transparent pointer-events-none z-10" />
    </div>
  )
}
