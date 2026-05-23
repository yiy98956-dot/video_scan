import { useState, useEffect, useCallback } from 'react'
import { useAuth } from '../lib/auth'
import { commentApi, type CommentItem } from '../lib/interactionApi'

interface Props {
  videoId: number
}

function timeAgo(t: string): string {
  const diff = Date.now() - new Date(t).getTime()
  const m = Math.floor(diff / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return `${m}分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}小时前`
  const d = Math.floor(h / 24)
  if (d < 30) return `${d}天前`
  return new Date(t).toLocaleDateString('zh-CN')
}

export default function CommentSection({ videoId }: Props) {
  const { user } = useAuth()
  const [comments, setComments] = useState<CommentItem[]>([])
  const [page, setPage] = useState(1)
  const [hasMore, setHasMore] = useState(true)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [content, setContent] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const loadComments = useCallback(async (p: number) => {
    setLoading(true)
    setError(null)
    try {
      const data = await commentApi.list(videoId, p, 10, 'time')
      const items = data?.items || []
      if (p === 1) setComments(items)
      else setComments(prev => [...prev, ...items])
      setHasMore(items.length >= 10)
      setPage(p)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [videoId])

  useEffect(() => { loadComments(1) }, [loadComments])

  const handleSubmit = async () => {
    if (!content.trim() || !user) return
    setSubmitting(true)
    try {
      await commentApi.create(videoId, content.trim())
      setContent('')
      loadComments(1)
    } catch { /* silent */ }
    finally { setSubmitting(false) }
  }

  const handleLike = async (commentId: number) => {
    try {
      await commentApi.like(commentId)
      setComments(prev => prev.map(c =>
        c.id === commentId ? { ...c, likeCount: c.likeCount + 1 } : c
      ))
    } catch { /* silent */ }
  }

  return (
    <div className="mt-12 animate-fade-up">
      <h3 className="text-sm text-text font-medium mb-6">评论</h3>

      {/* 发表评论 */}
      {user ? (
        <div className="flex gap-3 mb-8">
          <input type="text" value={content} onChange={(e) => setContent(e.target.value)}
            placeholder="写下你的评论..."
            className="flex-1 h-10 px-4 rounded-lg bg-surface border border-border text-text text-sm
              placeholder:text-text-secondary/30 focus:outline-none focus:border-gold/30 transition-all duration-200"
            onKeyDown={(e) => e.key === 'Enter' && handleSubmit()} />
          <button onClick={handleSubmit} disabled={!content.trim() || submitting}
            className="px-5 h-10 rounded-lg text-xs border border-gold/20 text-gold
              hover:bg-gold/5 transition-all duration-200 disabled:opacity-40 disabled:cursor-not-allowed">
            {submitting ? '发送中' : '发送'}
          </button>
        </div>
      ) : (
        <p className="text-text-secondary text-xs mb-8">登录后即可评论</p>
      )}

      {/* 评论列表 */}
      {loading && page === 1 ? (
        <div className="space-y-4">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="flex gap-3 animate-shimmer-gold">
              <div className="w-8 h-8 rounded-full bg-surface-2 flex-shrink-0" />
              <div className="flex-1 space-y-2">
                <div className="h-3 bg-surface-2 rounded w-20" />
                <div className="h-3 bg-surface-2 rounded w-3/4" />
              </div>
            </div>
          ))}
        </div>
      ) : error ? (
        <div className="text-center py-8">
          <p className="text-text-secondary text-sm mb-3">{error}</p>
          <button onClick={() => loadComments(1)}
            className="text-xs text-gold hover:text-gold-light transition-colors">重试</button>
        </div>
      ) : comments.length === 0 ? (
        <p className="text-text-secondary text-xs text-center py-8">暂无评论</p>
      ) : (
        <div className="space-y-5">
          {comments.map((c) => (
            <div key={c.id} className="flex gap-3">
              <div className="w-8 h-8 rounded-full bg-surface-2 flex items-center justify-center text-xs text-text-secondary flex-shrink-0">
                {c.avatarUrl ? (
                  <img src={c.avatarUrl} alt="" className="w-full h-full rounded-full object-cover"
                    onError={(e) => { (e.target as HTMLImageElement).style.display = 'none' }} />
                ) : c.nickname?.[0] || '?'}
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <span className="text-xs text-text font-medium">{c.nickname}</span>
                  <span className="text-[10px] text-text-secondary">{timeAgo(c.createTime)}</span>
                </div>
                <p className="text-sm text-text-secondary leading-relaxed">{c.content}</p>
                <div className="flex items-center gap-3 mt-1.5">
                  <button onClick={() => handleLike(c.id)}
                    className="text-[10px] text-text-secondary hover:text-gold transition-colors">
                    {c.likeCount > 0 ? `赞 ${c.likeCount}` : '赞'}
                  </button>
                </div>

                {/* 回复预展 */}
                {c.replies && c.replies.length > 0 && (
                  <div className="mt-2 pl-3 border-l border-border space-y-2">
                    {c.replies.map((r) => (
                      <div key={r.id} className="text-sm text-text-secondary">
                        <span className="text-text text-xs">{r.nickname}</span>
                        {r.replyToUid && <span className="text-text-secondary/60"> 回复 </span>}
                        <span>{r.content}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          ))}

          {hasMore && (
            <div className="text-center pt-4">
              <button onClick={() => loadComments(page + 1)} disabled={loading}
                className="text-xs text-gold hover:text-gold-light transition-colors disabled:opacity-40">
                {loading ? '加载中...' : '加载更多'}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
