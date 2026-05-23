import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router-dom'

// 这些路径进入时总是从顶部开始，不恢复滚动位置
const RESET_PATHS = ['/', '/auth', '/play']

// 这些路径不保存滚动位置（详情页/播放页返回时由浏览器原生后退处理）
const NO_SAVE_PATHS = ['/detail/', '/play/']

function savePos(path: string) {
  try { sessionStorage.setItem('scroll:' + path, String(window.scrollY)) } catch {}
}
function getPos(path: string): number {
  try {
    const v = sessionStorage.getItem('scroll:' + path)
    return v ? parseInt(v, 10) : 0
  } catch { return 0 }
}
function shouldReset(path: string) {
  return RESET_PATHS.some(p => path === p || path.startsWith(p + '/'))
}
function shouldNotSave(path: string) {
  return NO_SAVE_PATHS.some(p => path.includes(p))
}

export default function ScrollToTop() {
  const { pathname } = useLocation()
  const prevPath = useRef(pathname)

  useEffect(() => {
    const prev = prevPath.current
    // 离开当前页时保存滚动位置（详情页/播放页除外）
    if (prev !== pathname && !shouldNotSave(prev)) {
      savePos(prev)
    }
    prevPath.current = pathname
  }, [pathname])

  useEffect(() => {
    if (shouldReset(pathname)) {
      window.scrollTo({ top: 0, behavior: 'instant' as ScrollBehavior })
      return
    }
    // 详情页进入时滚到顶部
    if (pathname.startsWith('/detail/')) {
      window.scrollTo({ top: 0, behavior: 'instant' as ScrollBehavior })
      return
    }
    // 其他页面恢复滚动位置
    const pos = getPos(pathname)
    requestAnimationFrame(() => {
      window.scrollTo({ top: pos, behavior: 'instant' as ScrollBehavior })
    })
  }, [pathname])

  return null
}
