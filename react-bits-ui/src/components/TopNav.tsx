import { useState, useEffect, useRef } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useAuth } from '../lib/auth'

const navLinks = [
  { to: '/', label: '首页' },
  { to: '/browse', label: '浏览' },
  { to: '/categories', label: '分类' },
  { to: '/search', label: '搜索' },
]

export default function TopNav() {
  const { pathname } = useLocation()
  const { user, loading: authLoading, logout } = useAuth()
  const [scrolled, setScrolled] = useState(false)
  const [adminMenuOpen, setAdminMenuOpen] = useState(false)
  const adminMenuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 20)
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  // 点击外部关闭管理菜单
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (adminMenuRef.current && !adminMenuRef.current.contains(e.target as Node)) {
        setAdminMenuOpen(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  return (
    <nav className={`fixed top-0 left-0 right-0 z-50 transition-all duration-500 ${
      scrolled ? 'glass shadow-[0_2px_20px_rgba(0,0,0,0.4)]' : 'bg-bg/80 backdrop-blur-md'
    }`}>
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center justify-between h-14 md:h-16">
          {/* Logo */}
          <Link to="/"
            className="text-xl md:text-2xl font-serif-en italic font-bold text-gold hover:text-gold-light transition-colors duration-300">
            影视星河
          </Link>

          {/* 桌面端导航链接 — 仅 md 以上显示 */}
          <div className="hidden md:flex items-center gap-8">
            {navLinks.map((link) => {
              const isActive = link.to === '/'
                ? pathname === '/'
                : pathname.startsWith(link.to)
              return (
                <Link key={link.to} to={link.to}
                  className={`text-sm tracking-wide transition-colors duration-300 ${
                    isActive ? 'text-gold' : 'text-text-secondary hover:text-text'
                  }`}>
                  {link.label}
                </Link>
              )
            })}
          </div>

          {/* 用户区域 */}
          <div className="flex items-center gap-3">
            {authLoading ? null : user ? (
              <div className="flex items-center gap-2">
                <Link to="/profile"
                  className="text-xs text-text-secondary hover:text-gold transition-colors hidden sm:inline">
                  {user.nickname || user.username}
                </Link>
                {user.role === 'admin' && (
                  <div className="relative" ref={adminMenuRef}>
                    <button onClick={() => setAdminMenuOpen(!adminMenuOpen)}
                      className="text-[10px] text-gold/60 hover:text-gold transition-colors ml-1">
                      管理 ▾
                    </button>
                    {adminMenuOpen && (
                      <div className="absolute right-0 top-full mt-1 w-28 bg-surface border border-border rounded-lg shadow-lg overflow-hidden">
                        <Link to="/admin/users"
                          className="block px-4 py-2 text-xs text-text-secondary hover:bg-surface-2 hover:text-text transition-colors">
                          用户管理
                        </Link>
                        <Link to="/admin/categories"
                          className="block px-4 py-2 text-xs text-text-secondary hover:bg-surface-2 hover:text-text transition-colors">
                          分类管理
                        </Link>
                      </div>
                    )}
                  </div>
                )}
                <button onClick={logout}
                  className="text-xs text-text-secondary hover:text-text transition-colors">退出</button>
              </div>
            ) : (
              <Link to="/auth"
                className="px-4 py-1.5 rounded-lg text-xs border border-gold/20 text-gold
                  hover:bg-gold/5 transition-all duration-200">
                登录
              </Link>
            )}
          </div>
        </div>
      </div>
    </nav>
  )
}
