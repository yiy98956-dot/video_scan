import { Link, useLocation } from 'react-router-dom'
import { useAuth } from '../lib/auth'

const tabs = [
  {
    to: '/',
    label: '首页',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} className="w-5 h-5">
        <path strokeLinecap="round" strokeLinejoin="round" d="M2.25 12l8.954-8.955a1.126 1.126 0 011.591 0L21.75 12M4.5 9.75v10.125c0 .621.504 1.125 1.125 1.125H9.75v-4.875c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125V21h4.125c.621 0 1.125-.504 1.125-1.125V9.75M8.25 21h8.25" />
      </svg>
    ),
  },
  {
    to: '/categories',
    label: '分类',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} className="w-5 h-5">
        <path strokeLinecap="round" strokeLinejoin="round" d="M3.75 6A2.25 2.25 0 016 3.75h2.25A2.25 2.25 0 0110.5 6v2.25a2.25 2.25 0 01-2.25 2.25H6a2.25 2.25 0 01-2.25-2.25V6zM3.75 15.75A2.25 2.25 0 016 13.5h2.25a2.25 2.25 0 012.25 2.25V18a2.25 2.25 0 01-2.25 2.25H6A2.25 2.25 0 013.75 18v-2.25zM13.5 6a2.25 2.25 0 012.25-2.25H18A2.25 2.25 0 0120.25 6v2.25A2.25 2.25 0 0118 10.5h-2.25a2.25 2.25 0 01-2.25-2.25V6zM13.5 15.75a2.25 2.25 0 012.25-2.25H18a2.25 2.25 0 012.25 2.25V18A2.25 2.25 0 0118 20.25h-2.25A2.25 2.25 0 0113.5 18v-2.25z" />
      </svg>
    ),
  },
  {
    to: '/search',
    label: '搜索',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} className="w-5 h-5">
        <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" />
      </svg>
    ),
  },
  {
    to: '/profile',
    label: '我的',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.5} className="w-5 h-5">
        <path strokeLinecap="round" strokeLinejoin="round" d="M15.75 6a3.75 3.75 0 11-7.5 0 3.75 3.75 0 017.5 0zM4.501 20.118a7.5 7.5 0 0114.998 0A17.933 17.933 0 0112 21.75c-2.676 0-5.216-.584-7.499-1.632z" />
      </svg>
    ),
  },
]

export default function BottomNav() {
  const { pathname } = useLocation()
  const { user } = useAuth()

  const isActive = (to: string) => {
    if (to === '/') return pathname === '/'
    return pathname.startsWith(to)
  }

  return (
    <nav className="fixed bottom-0 left-0 right-0 z-50 md:hidden glass border-t border-border">
      <div className="max-w-lg mx-auto flex items-center justify-around h-14 px-2">
        {tabs.map((tab) => {
          const active = isActive(tab.to)
          // "我的" 页面未登录时跳转到登录
          const href = tab.to === '/profile' && !user ? '/auth' : tab.to
          return (
            <Link
              key={tab.to}
              to={href}
              className={`flex flex-col items-center justify-center gap-0.5 flex-1 h-full transition-colors duration-200 ${
                active ? 'text-gold' : 'text-text-secondary/60 hover:text-text-secondary'
              }`}
            >
              <span className={`transition-transform duration-200 ${active ? 'scale-110' : ''}`}>
                {tab.icon}
              </span>
              <span className="text-[10px]">{tab.label}</span>
              {active && (
                <span className="absolute bottom-1 w-4 h-0.5 rounded-full bg-gold" />
              )}
            </Link>
          )
        })}
      </div>
    </nav>
  )
}
