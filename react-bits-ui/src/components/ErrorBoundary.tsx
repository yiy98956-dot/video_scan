import { Component, type ReactNode, type ErrorInfo } from 'react'

interface Props {
  children: ReactNode
  fallback?: (error: Error) => ReactNode
}

interface State {
  hasError: boolean
  error: Error | null
}

export default class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props)
    this.state = { hasError: false, error: null }
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary]', error, info.componentStack)
  }

  render() {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback(this.state.error!)
      }
      return (
        <div className="min-h-screen bg-bg flex flex-col items-center justify-center px-4">
          <div className="w-16 h-16 rounded-full bg-surface border border-border flex items-center justify-center mb-6">
            <svg className="w-7 h-7 text-gold" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5}
                d="M12 9v3.75m9-.75a9 9 0 11-18 0 9 9 0 0118 0zm-9 3.75h.008v.008H12v-.008z" />
            </svg>
          </div>
          <h2 className="text-lg text-text font-medium mb-2">页面出错了</h2>
          <p className="text-text-secondary text-sm mb-6 text-center max-w-md">
            {this.state.error?.message || '发生了未知错误'}
          </p>
          <button
            onClick={() => { this.setState({ hasError: false, error: null }); window.location.href = '/' }}
            className="px-6 py-2.5 rounded-lg border border-gold/20 text-gold text-sm hover:bg-gold/5 transition-all duration-300"
          >
            返回首页
          </button>
        </div>
      )
    }
    return this.props.children
  }
}
