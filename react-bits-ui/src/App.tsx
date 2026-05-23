import { lazy, Suspense } from 'react'
import { Routes, Route, useLocation } from 'react-router-dom'
import TopNav from './components/TopNav'
import BottomNav from './components/BottomNav'
import ScrollToTop from './components/ScrollToTop'
import ErrorBoundary from './components/ErrorBoundary'
import { AuthProvider } from './lib/auth'
import PageTransition from './components/PageTransition'
import LoadingSkeleton from './components/LoadingSkeleton'

// ── 懒加载页面（减少首屏 JS 体积） ──
const Home = lazy(() => import('./pages/Home'))
const Browse = lazy(() => import('./pages/Browse'))
const Detail = lazy(() => import('./pages/Detail'))
const Play = lazy(() => import('./pages/Play'))
const Search = lazy(() => import('./pages/Search'))
const Categories = lazy(() => import('./pages/Categories'))
const Auth = lazy(() => import('./pages/Auth'))
const Profile = lazy(() => import('./pages/Profile'))
const AdminCategories = lazy(() => import('./pages/AdminCategories'))
const AdminUsers = lazy(() => import('./pages/AdminUsers'))
const NotFound = lazy(() => import('./pages/NotFound'))

// ── 全局 Suspense fallback ──
function PageLoader() {
  return (
    <div className="min-h-screen flex items-center justify-center bg-bg pt-16">
      <LoadingSkeleton variant="card" count={3} />
    </div>
  )
}

export default function App() {
  const location = useLocation()

  // 播放页和详情页不显示底部导航
  const hideBottomNav = location.pathname.startsWith('/play') || location.pathname.startsWith('/auth')

  return (
    <div className="relative min-h-screen">
      <AuthProvider>
        <ErrorBoundary>
          <ScrollToTop />
          <TopNav />
          <main className={`pt-14 md:pt-16 ${hideBottomNav ? '' : 'pb-14 md:pb-0'}`}>
            <Suspense fallback={<PageLoader />}>
              <PageTransition>
                <Routes location={location} key={location.pathname}>
                  <Route path="/" element={<Home />} />
                  <Route path="/browse" element={<Browse />} />
                  <Route path="/search" element={<Search />} />
                  <Route path="/categories" element={<Categories />} />
                  <Route path="/detail/:id" element={<Detail />} />
                  <Route path="/play/:id" element={<Play />} />
                  <Route path="/auth" element={<Auth />} />
                  <Route path="/profile" element={<Profile />} />
                  <Route path="/admin/categories" element={<AdminCategories />} />
                  <Route path="/admin/users" element={<AdminUsers />} />
                  <Route path="*" element={<NotFound />} />
                </Routes>
              </PageTransition>
            </Suspense>
          </main>
          {!hideBottomNav && <BottomNav />}
        </ErrorBoundary>
      </AuthProvider>
    </div>
  )
}
