import { Link } from 'react-router-dom'

export default function NotFound() {
  return (
    <div className="min-h-[70vh] flex flex-col items-center justify-center px-4 animate-fade-up">
      <div className="text-8xl font-serif-en italic font-bold text-gold/20 mb-4">404</div>
      <h1 className="text-xl text-text font-medium mb-2">页面未找到</h1>
      <p className="text-sm text-text-secondary mb-8">你访问的页面不存在或已被移除</p>
      <div className="flex items-center gap-3">
        <Link to="/"
          className="px-5 py-2 rounded-lg bg-gold/8 text-gold border border-gold/25 text-sm
            hover:bg-gold/12 transition-all duration-300">
          返回首页
        </Link>
        <Link to="/search"
          className="px-5 py-2 rounded-lg bg-surface text-text-secondary border border-border text-sm
            hover:border-gold/15 hover:text-text transition-all duration-300">
          搜索内容
        </Link>
      </div>
    </div>
  )
}
