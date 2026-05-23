import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '../lib/auth'

type Mode = 'login' | 'register'

export default function Auth() {
  const navigate = useNavigate()
  const { login, register } = useAuth()
  const [mode, setMode] = useState<Mode>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      if (mode === 'login') await login(username, password)
      else await register(username, password)
      navigate('/')
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen pt-24 px-4 sm:px-6 lg:px-8 flex items-start justify-center">
      <div className="w-full max-w-sm mt-16">
        <div className="text-center mb-8 animate-fade-up">
          <h1 className="text-2xl text-text font-medium">
            {mode === 'login' ? '登录' : '注册'}
          </h1>
          <p className="text-text-secondary text-sm mt-2">
            {mode === 'login' ? '欢迎回来' : '创建你的账户'}
          </p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-5 animate-fade-up" style={{ animationDelay: '0.1s' }}>
          {error && (
            <div className="px-4 py-2.5 rounded-lg bg-rose-900/20 border border-rose-700/30 text-rose-300 text-xs">
              {error}
            </div>
          )}
          <div>
            <label className="block text-xs text-text-secondary mb-1.5">用户名</label>
            <input type="text" value={username} onChange={(e) => setUsername(e.target.value)}
              className="w-full h-10 px-4 rounded-lg bg-surface border border-border text-text text-sm
                placeholder:text-text-secondary/30 focus:outline-none focus:border-gold/30 transition-all duration-200"
              placeholder="请输入用户名" required minLength={2} />
          </div>
          <div>
            <label className="block text-xs text-text-secondary mb-1.5">密码</label>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
              className="w-full h-10 px-4 rounded-lg bg-surface border border-border text-text text-sm
                placeholder:text-text-secondary/30 focus:outline-none focus:border-gold/30 transition-all duration-200"
              placeholder="请输入密码" required minLength={6} />
          </div>
          <button type="submit" disabled={loading}
            className="w-full h-10 rounded-lg border border-gold/20 text-gold text-sm
              hover:bg-gold/5 transition-all duration-300 disabled:opacity-40 disabled:cursor-not-allowed">
            {loading ? '处理中...' : (mode === 'login' ? '登录' : '注册')}
          </button>
        </form>

        <div className="text-center mt-6 animate-fade-up" style={{ animationDelay: '0.2s' }}>
          <button onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError(null) }}
            className="text-xs text-text-secondary hover:text-text transition-colors duration-200">
            {mode === 'login' ? '没有账户？注册' : '已有账户？登录'}
          </button>
        </div>
      </div>
    </div>
  )
}
