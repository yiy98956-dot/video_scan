import { useState } from 'react'
import { useAuth } from '../lib/auth'
import { interactionApi } from '../lib/interactionApi'

interface Props {
  videoId: number
  source?: string
  initialLiked?: boolean
  initialFavorited?: boolean
  likeCount: number
  collectCount: number
  commentCount: number
}

export default function InteractionBar({ videoId, source, initialLiked, initialFavorited, likeCount, collectCount, commentCount }: Props) {
  const { user } = useAuth()
  const [liked, setLiked] = useState(initialLiked || false)
  const [favorited, setFavorited] = useState(initialFavorited || false)
  const [lc, setLc] = useState(likeCount)
  const [cc, setCc] = useState(collectCount)
  const [likeLoading, setLikeLoading] = useState(false)
  const [favLoading, setFavLoading] = useState(false)

  const handleLike = async () => {
    if (!user) return
    setLikeLoading(true)
    try {
      const res = await interactionApi.toggleLike(videoId, source)
      setLiked(res.isLiked)
      setLc(res.likeCount)
    } catch { /* silent */ }
    finally { setLikeLoading(false) }
  }

  const handleFavorite = async () => {
    if (!user) return
    setFavLoading(true)
    try {
      const res = await interactionApi.toggleFavorite(videoId, source)
      setFavorited(res.isFavorited)
      setCc(res.collectCount)
    } catch { /* silent */ }
    finally { setFavLoading(false) }
  }

  return (
    <div className="flex items-center gap-3">
      <button onClick={handleLike} disabled={!user || likeLoading}
        className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs transition-all duration-200 ${
          liked ? 'bg-rose-900/20 text-rose-300 border border-rose-700/30'
                : 'bg-surface border border-border text-text-secondary hover:border-gold/15'
        } disabled:opacity-40 disabled:cursor-not-allowed`}>
        <svg className="w-3.5 h-3.5" fill={liked ? 'currentColor' : 'none'} viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z" />
        </svg>
        {lc > 0 && <span>{lc}</span>}
      </button>

      <button onClick={handleFavorite} disabled={!user || favLoading}
        className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs transition-all duration-200 ${
          favorited ? 'bg-amber-900/20 text-amber-300 border border-amber-700/30'
                    : 'bg-surface border border-border text-text-secondary hover:border-gold/15'
        } disabled:opacity-40 disabled:cursor-not-allowed`}>
        <svg className="w-3.5 h-3.5" fill={favorited ? 'currentColor' : 'none'} viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M5 5a2 2 0 012-2h10a2 2 0 012 2v16l-7-3.5L5 21V5z" />
        </svg>
        {cc > 0 && <span>{cc}</span>}
      </button>

      {/* 评论数 */}
      <div className="flex items-center gap-1 text-xs text-text-secondary">
        <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
            d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
        </svg>
        <span>{commentCount}</span>
      </div>
    </div>
  )
}
