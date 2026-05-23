import { useState, useCallback } from 'react'
import type { VideoItemVO, SearchResult } from '../lib/types'
import { searchApi } from '../lib/api'
import MovieCard from '../components/MovieCard'
import LoadingSkeleton from '../components/LoadingSkeleton'
import ErrorState from '../components/ErrorState'
import EmptyState from '../components/EmptyState'
import Pagination from '../components/Pagination'

export default function Search() {
  const [keyword, setKeyword] = useState('')
  const [results, setResults] = useState<VideoItemVO[]>([])
  const [total, setTotal] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [currentPage, setCurrentPage] = useState(1)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [searched, setSearched] = useState(false)

  const doSearch = useCallback(async (kw: string, page: number) => {
    if (!kw.trim()) return
    setLoading(true)
    setError(null)
    setSearched(true)
    try {
      const res: SearchResult = await searchApi.search(kw.trim(), page, 20)
      setResults(res.items)
      setTotal(res.total)
      setCurrentPage(res.page)
      setTotalPages(Math.ceil(res.total / res.size))
    } catch (err) {
      setError(err instanceof Error ? err.message : '搜索失败')
      setResults([])
    } finally {
      setLoading(false)
    }
  }, [])

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setCurrentPage(1)
    doSearch(keyword, 1)
  }

  const handlePageChange = (page: number) => {
    setCurrentPage(page)
    doSearch(keyword, page)
  }

  return (
    <div className="min-h-screen pt-24 px-4 sm:px-6 lg:px-8">
      <div className="max-w-3xl mx-auto">
        {/* Search box */}
        <div className="mb-10 animate-fade-up">
          <h1 className="text-2xl text-text font-medium mb-6 text-center">搜索</h1>
          <form onSubmit={handleSubmit}>
            <div className="relative">
              <input
                type="text"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                placeholder="输入关键词，探索光影世界"
                className="w-full h-12 px-5 pr-12 rounded-xl bg-surface border border-border text-text text-sm
                  placeholder:text-text-secondary/40
                  focus:outline-none focus:border-gold/30 focus:shadow-[0_0_0_3px_rgba(201,169,110,0.08)]
                  transition-all duration-300"
              />
              <button
                type="submit"
                className="absolute right-3 top-1/2 -translate-y-1/2 p-2 text-text-secondary hover:text-gold transition-colors duration-200"
              >
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                    d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" />
                </svg>
              </button>
            </div>
          </form>
        </div>

        {/* Results */}
        {!searched ? (
          <div className="text-center py-20">
            <div className="w-16 h-16 rounded-full bg-surface border border-border flex items-center justify-center mx-auto mb-5">
              <svg className="w-7 h-7 text-text-secondary" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                  d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" />
              </svg>
            </div>
            <p className="text-text-secondary text-sm">输入关键词，探索光影世界</p>
          </div>
        ) : loading ? (
          <LoadingSkeleton variant="card" count={12} />
        ) : error ? (
          <ErrorState message={error} onRetry={() => doSearch(keyword, currentPage)} />
        ) : results.length === 0 ? (
          <EmptyState message={`未找到「${keyword}」相关的作品`} hint="试试其他关键词" />
        ) : (
          <>
            <p className="text-xs text-text-secondary mb-6">找到 {total} 部作品</p>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-4">
              {results.map((video) => (
                <MovieCard key={video.cmsVideoId} video={video} />
              ))}
            </div>
            <Pagination
              current={currentPage}
              total={totalPages}
              onChange={handlePageChange}
            />
          </>
        )}
      </div>
    </div>
  )
}
