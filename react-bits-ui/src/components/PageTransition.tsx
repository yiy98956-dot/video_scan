import { useLocation } from 'react-router-dom'
import { useEffect, useState, type ReactNode } from 'react'

interface Props {
  children: ReactNode
}

export default function PageTransition({ children }: Props) {
  const location = useLocation()
  const [displayChildren, setDisplayChildren] = useState(children)
  const [transitionClass, setTransitionClass] = useState('')

  useEffect(() => {
    // 退出动画
    setTransitionClass('page-exit')
    const exitTimer = setTimeout(() => {
      setDisplayChildren(children)
      setTransitionClass('page-enter')
      // 进入动画结束后清除
      const enterTimer = setTimeout(() => {
        setTransitionClass('')
      }, 250)
      return () => clearTimeout(enterTimer)
    }, 150)

    return () => clearTimeout(exitTimer)
  }, [location.pathname])

  return (
    <div className={`page-transition ${transitionClass}`}>
      {displayChildren}
    </div>
  )
}
