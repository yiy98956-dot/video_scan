import { useEffect, useRef, useState, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { videoApi, historyApi, streamProxyUrl } from '../lib/api'
import type { VideoDetailVO } from '../lib/types'
import Hls from 'hls.js'

type PlayState = '' | 'loading' | 'ready' | 'error'

// 深度优化 HLS 配置 - 解决卡顿和缓冲问题
const HLS_CONFIG = {
  enableWorker: true,
  lowLatencyMode: false,

  // ===== 缓冲区优化 =====
  // 增加缓冲区大小，减少卡顿
  maxBufferLength: 120,           // 最大缓冲 120 秒（原来是 60）
  maxMaxBufferLength: 300,        // 绝对最大 300 秒（原来是 120）
  maxBufferSize: 100 * 1000 * 1000, // 100MB 缓冲（原来是 50MB）
  maxBufferHole: 1.0,             // 允许 1 秒的空洞（原来是 0.5）

  // ===== 启动优化 =====
  // 快速启动，减少等待时间
  startLevel: -1,                 // 自动选择清晰度
  startFragPrefetch: true,        // 预加载第一个分片

  // ===== ABR 自适应码率优化 =====
  // 更保守的切换策略，避免频繁切换导致卡顿
  abrEwmaDefaultEstimate: 300000, // 默认 300kbps（降低初始估计）
  abrBandWidthFactor: 0.8,        // 使用 80% 带宽（更保守）
  abrBandWidthUpFactor: 0.6,      // 升码率更保守
  abrMaxWithRealBitrate: true,    // 使用真实码率

  // ===== 加载策略优化 =====
  // 更激进的预加载
  maxFragLookUpTolerance: 3.0,    // 分片查找容差
  liveSyncDurationCount: 5,       // 直播同步计数
  liveMaxLatencyDurationCount: 10, // 直播最大延迟

  // ===== 重试策略优化 =====
  // 更积极的错误恢复
  manifestLoadingMaxRetry: 5,     // 增加重试次数
  levelLoadingMaxRetry: 5,
  fragLoadingMaxRetry: 6,
  manifestLoadingRetryDelay: 500, // 减少重试延迟
  levelLoadingRetryDelay: 500,
  fragLoadingRetryDelay: 500,
  manifestLoadingTimeOut: 20000,  // 增加超时时间
  levelLoadingTimeOut: 20000,
  fragLoadingTimeOut: 20000,

  // ===== 后台缓冲优化 =====
  backBufferLength: 60,           // 保留 60 秒已播放缓冲（原来是 30）

  // ===== 分片加载优化 =====
  fragLoadingLoopThreshold: 1000, // 分片加载循环阈值
  appendErrorMaxRetry: 5,         // 追加错误重试

  // ===== 直播优化 =====
  liveDurationInfinity: true,
  liveBackBufferLength: 60,
}

const scheduleReport = (callback: () => void) => {
  if ('requestIdleCallback' in window) {
    window.requestIdleCallback(callback, { timeout: 2000 })
  } else {
    setTimeout(callback, 100)
  }
}

export default function Play() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const sourceParam = new URLSearchParams(window.location.search).get('source') || undefined

  const videoRef = useRef<HTMLVideoElement>(null)
  const playerRef = useRef<HTMLDivElement>(null)
  const hlsRef = useRef<Hls | null>(null)
  const progressTimer = useRef<ReturnType<typeof setInterval> | undefined>(undefined)
  const hideTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const lastReportRef = useRef(0)
  const epJumping = useRef(false)
  const rafId = useRef<number>()
  const bufferedEndRef = useRef(0)

  const [detail, setDetail] = useState<VideoDetailVO | null>(null)
  const [loading, setLoading] = useState(true)
  const [playState, setPlayState] = useState<PlayState>('')
  const [errorMsg, setErrorMsg] = useState('')
  const [isPlaying, setIsPlaying] = useState(false)

  const [activeSource, setActiveSource] = useState(0)
  const [activeEp, setActiveEp] = useState(0)

  const [showControls, setShowControls] = useState(true)
  const [currentTime, setCurrentTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [bufPct, setBufPct] = useState(0)
  const [volume, setVolume] = useState(1)
  const [isMuted, setIsMuted] = useState(false)
  const [speed, setSpeed] = useState(1)
  const [isFullscreen, setIsFullscreen] = useState(false)
  const [quality, setQuality] = useState<string>('auto')
  const [showEpisodes, setShowEpisodes] = useState(false)
  const [isDragging, setIsDragging] = useState(false)
  const [availableLevels, setAvailableLevels] = useState<Array<{height: number, bitrate: number}>>([])
  const [manualQuality, setManualQuality] = useState<number>(-1) // -1 = auto

  const currentSource = detail?.plays?.[activeSource]
  const totalEps = detail?.plays?.reduce((s, p) => s + (p.urls?.length || 0), 0) || 0

  const [savedProgress, setSavedProgress] = useState(0)

  useEffect(() => {
    if (!id) return
    setLoading(true)

    Promise.all([
      videoApi.detail(Number(id), sourceParam),
      historyApi.getProgress(Number(id))
    ]).then(([data, progress]) => {
      setDetail(data)
      if (progress > 5) setSavedProgress(progress)
      setLoading(false)
    }).catch(() => {
      setLoading(false)
      setPlayState('error')
      setErrorMsg('加载失败')
    })
  }, [id, sourceParam])

  useEffect(() => {
    const updateProgress = () => {
      const v = videoRef.current
      if (v) {
        setCurrentTime(v.currentTime)
        setDuration(v.duration || 0)

        if (v.buffered.length > 0) {
          const end = v.buffered.end(v.buffered.length - 1)
          bufferedEndRef.current = end
          const pct = (end / (v.duration || 1)) * 100
          setBufPct(Math.min(pct, 100))
        }
      }
      rafId.current = requestAnimationFrame(updateProgress)
    }
    rafId.current = requestAnimationFrame(updateProgress)
    return () => {
      if (rafId.current) cancelAnimationFrame(rafId.current)
    }
  }, [])

  const playFrom = useCallback((si: number, ei: number, url: string) => {
    if (!url || !videoRef.current) return
    epJumping.current = true
    setActiveSource(si)
    setActiveEp(ei)
    setPlayState('loading')
    setErrorMsg('')

    const v = videoRef.current
    stopHls()

    const proxyUrl = streamProxyUrl(url)
    const isM3u8 = url.includes('.m3u8')
    const seek = savedProgress

    if (isM3u8 && Hls.isSupported()) {
      v.removeAttribute('src')
      const h = new Hls(HLS_CONFIG)
      hlsRef.current = h

      // 保存可用的清晰度列表
      h.on(Hls.Events.MANIFEST_LOADED, (_, data) => {
        if (data.levels) {
          setAvailableLevels(data.levels.map(l => ({ height: l.height || 0, bitrate: l.bitrate || 0 })))
        }
      })

      h.on(Hls.Events.LEVEL_SWITCHED, (_, data) => {
        const levels = h.levels
        if (levels[data.level]) {
          setQuality(`${levels[data.level].height}p`)
        }
      })

      // 缓冲状态监控
      h.on(Hls.Events.BUFFER_APPENDED, () => {
        // 预加载更多数据
        if (h) {
          const buffered = v.buffered
          if (buffered.length > 0) {
            const bufferedEnd = buffered.end(buffered.length - 1)
            const duration = v.duration || 0
            // 如果缓冲少于 60 秒，尝试加载更多
            if (duration - bufferedEnd < 60) {
              h.trigger(Hls.Events.BUFFER_EOS)
            }
          }
        }
      })

      h.loadSource(proxyUrl)
      h.attachMedia(v)

      h.on(Hls.Events.MANIFEST_PARSED, (_, data) => {
        setPlayState('ready')

        // 应用手动清晰度设置
        if (manualQuality >= 0 && data.levels.length > manualQuality) {
          h.currentLevel = manualQuality
        } else if (data.levels.length > 1) {
          // 从较低清晰度开始，避免卡顿
          h.currentLevel = Math.max(0, data.levels.length - 2)
          // 5秒后切换到自动
          setTimeout(() => {
            if (hlsRef.current && manualQuality < 0) {
              hlsRef.current.currentLevel = -1
            }
          }, 5000)
        }

        if (seek > 0) v.currentTime = seek
        v.play().catch(() => {})
        setIsPlaying(true)
        epJumping.current = false
      })

      h.on(Hls.Events.ERROR, (_, data) => {
        console.error('[HLS Error]', data)
        if (data.fatal) {
          switch (data.type) {
            case Hls.ErrorTypes.NETWORK_ERROR:
              console.warn('[HLS] Network error, retrying...')
              h.startLoad()
              break
            case Hls.ErrorTypes.MEDIA_ERROR:
              console.warn('[HLS] Media error, recovering...')
              h.recoverMediaError()
              break
            default:
              stopHls()
              setPlayState('error')
              setErrorMsg('播放失败，请尝试切换线路或清晰度')
              break
          }
        } else {
          // 非致命错误，尝试恢复
          if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
            h.startLoad()
          }
        }
      })

      h.on(Hls.Events.BUFFER_STALLED, () => {
        console.warn('[HLS] Buffer stalled, trying to recover...')
        // 尝试降低清晰度
        if (h.currentLevel > 0) {
          h.currentLevel = h.currentLevel - 1
        }
      })

      h.on(Hls.Events.BUFFER_EMPTY, () => {
        console.warn('[HLS] Buffer empty, pausing...')
        setPlayState('loading')
      })

    } else if (isM3u8 && v.canPlayType('application/vnd.apple.mpegurl')) {
      v.src = proxyUrl
      v.load()
      v.addEventListener('loadedmetadata', () => {
        if (seek > 0) v.currentTime = seek
        v.play().catch(() => {})
        setIsPlaying(true)
        setPlayState('ready')
        epJumping.current = false
      }, { once: true })
    } else {
      v.src = proxyUrl
      v.load()
      v.addEventListener('loadedmetadata', () => {
        if (seek > 0) v.currentTime = seek
        v.play().catch(() => {})
        setIsPlaying(true)
        setPlayState('ready')
        epJumping.current = false
      }, { once: true })
    }

    startProgressReport()
  }, [savedProgress])

  useEffect(() => {
    if (detail?.plays?.[0]?.urls?.[0]) {
      const first = detail.plays[0].urls[0]
      playFrom(0, 0, first.url)
    }
  }, [detail, playFrom])

  const stopHls = () => {
    if (hlsRef.current) {
      hlsRef.current.destroy()
      hlsRef.current = null
    }
  }

  const startProgressReport = () => {
    clearInterval(progressTimer.current)
    progressTimer.current = setInterval(() => {
      const v = videoRef.current
      if (v && id && !v.paused) {
        const t = Math.floor(v.currentTime)
        const dur = Math.floor(v.duration || 0)
        if (t > 5 && Math.abs(t - lastReportRef.current) >= 30) {
          lastReportRef.current = t
          scheduleReport(() => {
            historyApi.report(Number(id), t, dur, sourceParam).catch(() => {})
          })
        }
      }
    }, 5000)
  }

  const forceReport = () => {
    const v = videoRef.current
    if (v && id) {
      const t = Math.floor(v.currentTime)
      if (t > 5) {
        scheduleReport(() => {
          historyApi.report(Number(id), t, Math.floor(v.duration || 0), sourceParam).catch(() => {})
        })
      }
    }
  }

  const switchSource = (si: number) => {
    const urls = detail?.plays?.[si]?.urls
    if (!urls?.length) return
    const ei = Math.min(activeEp, urls.length - 1)
    playFrom(si, ei, urls[ei].url)
  }

  const selectEp = (ei: number) => {
    const ep = currentSource?.urls?.[ei]
    if (ep) playFrom(activeSource, ei, ep.url)
  }

  const prevEp = () => {
    if (activeEp > 0) selectEp(activeEp - 1)
  }
  const nextEp = () => {
    const max = currentSource?.urls?.length || 0
    if (activeEp < max - 1) selectEp(activeEp + 1)
  }

  const togglePlay = () => {
    const v = videoRef.current
    if (!v) return
    if (v.paused) {
      v.play().catch(() => {})
      setIsPlaying(true)
    } else {
      v.pause()
      setIsPlaying(false)
      forceReport()
    }
    showControlsTmp()
  }

  const showControlsTmp = () => {
    setShowControls(true)
    clearTimeout(hideTimer.current)
    hideTimer.current = setTimeout(() => { setShowControls(false) }, 3000)
  }

  const toggleFullscreen = () => {
    const el = playerRef.current
    if (!el) return
    if (!document.fullscreenElement) {
      el.requestFullscreen().catch(() => {})
      setIsFullscreen(true)
    } else {
      document.exitFullscreen()
      setIsFullscreen(false)
    }
    showControlsTmp()
  }

  useEffect(() => {
    const onFsChange = () => setIsFullscreen(!!document.fullscreenElement)
    document.addEventListener('fullscreenchange', onFsChange)
    return () => document.removeEventListener('fullscreenchange', onFsChange)
  }, [])

  const seekTo = (ratio: number) => {
    const v = videoRef.current
    if (!v || !duration) return
    const t = ratio * duration
    v.currentTime = t
    setCurrentTime(t)
    showControlsTmp()
  }

  const changeVolume = (v: number) => {
    const video = videoRef.current
    if (!video) return
    video.volume = v
    setVolume(v)
    setIsMuted(v === 0)
  }

  const changeSpeed = (s: number) => {
    const v = videoRef.current
    if (!v) return
    v.playbackRate = s
    setSpeed(s)
    showControlsTmp()
  }

  useEffect(() => {
    return () => {
      stopHls()
      clearInterval(progressTimer.current)
      clearTimeout(hideTimer.current)
      forceReport()
    }
  }, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return
      switch (e.key) {
        case ' ': e.preventDefault(); togglePlay(); break
        case 'ArrowLeft': seekTo(Math.max(0, currentTime - 10) / duration); break
        case 'ArrowRight': seekTo(Math.min(duration, currentTime + 10) / duration); break
        case 'ArrowUp': changeVolume(Math.min(1, volume + 0.1)); break
        case 'ArrowDown': changeVolume(Math.max(0, volume - 0.1)); break
        case 'f': toggleFullscreen(); break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [currentTime, duration, volume])

  // 预加载下一集
  useEffect(() => {
    if (!detail?.plays || !currentSource?.urls) return

    const nextEpIndex = activeEp + 1
    const hasNextEp = nextEpIndex < (currentSource.urls.length || 0)

    // 当播放到还剩 90 秒时预加载下一集
    if (hasNextEp && duration > 0 && currentTime > duration - 90) {
      const nextUrl = currentSource.urls[nextEpIndex]?.url
      if (nextUrl) {
        const proxyUrl = streamProxyUrl(nextUrl)
        // 使用 prefetch 预加载
        const link = document.createElement('link')
        link.rel = 'prefetch'
        link.href = proxyUrl
        document.head.appendChild(link)

        // 如果是 m3u8，也预加载第一个分片
        if (nextUrl.includes('.m3u8')) {
          fetch(proxyUrl, { method: 'HEAD' }).catch(() => {})
        }

        return () => { document.head.removeChild(link) }
      }
    }
  }, [currentTime, duration, activeEp, currentSource, detail])

  // 网络状态监控 - 自动调整清晰度
  useEffect(() => {
    const handleNetworkChange = () => {
      const connection = (navigator as any).connection
      if (connection && hlsRef.current) {
        const h = hlsRef.current
        // 根据网络类型调整策略
        if (connection.effectiveType === '4g') {
          // 4G 网络，可以使用自动清晰度
          if (manualQuality < 0) h.currentLevel = -1
        } else if (connection.effectiveType === '3g') {
          // 3G 网络，限制清晰度
          if (h.levels.length > 1) {
            h.autoLevelCapping = Math.max(0, h.levels.length - 2)
          }
        } else if (connection.effectiveType === '2g' || connection.saveData) {
          // 2G 或省流量模式，使用最低清晰度
          h.currentLevel = 0
        }
      }
    }

    const connection = (navigator as any).connection
    if (connection) {
      connection.addEventListener('change', handleNetworkChange)
      handleNetworkChange()
      return () => connection.removeEventListener('change', handleNetworkChange)
    }
  }, [manualQuality])

  const fmt = (s: number) => {
    if (!isFinite(s) || s < 0) return '0:00'
    const m = Math.floor(s / 60)
    const sec = Math.floor(s % 60)
    return `${m}:${sec.toString().padStart(2, '0')}`
  }

  const formatTime = (s: number) => {
    if (!isFinite(s) || s < 0) return '00:00'
    const h = Math.floor(s / 3600)
    const m = Math.floor((s % 3600) / 60)
    const sec = Math.floor(s % 60)
    if (h > 0) {
      return `${h}:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}`
    }
    return `${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}`
  }

  const progressPercent = duration ? (currentTime / duration) * 100 : 0
  const bufferedPercent = bufPct

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-gray-900 via-black to-gray-900">
        <div className="text-center">
          <div className="relative w-16 h-16 mx-auto mb-6">
            <div className="absolute inset-0 border-4 border-gold/20 rounded-full" />
            <div className="absolute inset-0 border-4 border-gold border-t-transparent rounded-full animate-spin" />
          </div>
          <p className="text-white/60 text-sm tracking-wider">正在加载影片...</p>
        </div>
      </div>
    )
  }

  if (playState === 'error' && !detail) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-gray-900 via-black to-gray-900">
        <div className="text-center max-w-md px-6">
          <div className="w-20 h-20 mx-auto mb-6 rounded-full bg-red-500/10 flex items-center justify-center">
            <svg className="w-10 h-10 text-red-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
            </svg>
          </div>
          <p className="text-white text-lg mb-2">{errorMsg || '加载失败'}</p>
          <p className="text-white/50 text-sm mb-6">请检查网络连接或稍后重试</p>
          <button onClick={() => navigate(-1)} className="px-6 py-2.5 bg-gold text-black font-medium rounded-full hover:bg-gold/90 transition-colors">
            返回上一页
          </button>
        </div>
      </div>
    )
  }

  return (
    <div ref={playerRef} className="relative w-full h-screen bg-black overflow-hidden select-none"
      onMouseMove={showControlsTmp} onClick={showControlsTmp}>

      {/* 视频层 */}
      <video ref={videoRef} className="w-full h-full object-contain"
        playsInline preload="auto"
        onEnded={() => { setIsPlaying(false); forceReport(); nextEp() }}
        onWaiting={() => setPlayState('loading')}
        onPlaying={() => setPlayState('ready')}
        onError={() => setPlayState('error')} />

      {/* 加载状态 */}
      {playState === 'loading' && (
        <div className="absolute inset-0 flex items-center justify-center bg-black/40 backdrop-blur-sm pointer-events-none z-10">
          <div className="text-center">
            <div className="relative w-14 h-14 mx-auto mb-4">
              <div className="absolute inset-0 border-3 border-white/20 rounded-full" />
              <div className="absolute inset-0 border-3 border-gold border-t-transparent rounded-full animate-spin" />
            </div>
            <p className="text-white/80 text-sm">缓冲中...</p>
          </div>
        </div>
      )}

      {/* 错误状态 */}
      {playState === 'error' && (
        <div className="absolute inset-0 flex items-center justify-center bg-black/80 backdrop-blur-sm z-20">
          <div className="text-center max-w-sm px-6">
            <div className="w-16 h-16 mx-auto mb-4 rounded-full bg-red-500/10 flex items-center justify-center">
              <svg className="w-8 h-8 text-red-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M6 18L18 6M6 6l12 12" />
              </svg>
            </div>
            <p className="text-white mb-4">{errorMsg || '播放失败'}</p>
            <div className="flex gap-3 justify-center">
              <button onClick={() => window.location.reload()} className="px-5 py-2 bg-gold text-black text-sm font-medium rounded-full hover:bg-gold/90 transition-colors">
                重新加载
              </button>
              {detail?.plays && detail.plays.length > 1 && (
                <button onClick={() => switchSource((activeSource + 1) % detail.plays.length)} 
                  className="px-5 py-2 bg-white/10 text-white text-sm rounded-full hover:bg-white/20 transition-colors">
                  切换线路
                </button>
              )}
            </div>
          </div>
        </div>
      )}

      {/* 顶部渐变遮罩 + 信息栏 */}
      <div className={`absolute top-0 left-0 right-0 z-30 transition-all duration-500 ${showControls ? 'opacity-100 translate-y-0' : 'opacity-0 -translate-y-full'}`}>
        <div className="bg-gradient-to-b from-black/80 via-black/40 to-transparent pt-4 pb-12 px-6">
          <div className="flex items-center gap-4">
            <button onClick={() => navigate(-1)} 
              className="w-10 h-10 rounded-full bg-white/10 backdrop-blur flex items-center justify-center hover:bg-white/20 transition-all active:scale-95">
              <svg className="w-5 h-5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
              </svg>
            </button>
            
            <div className="flex-1 min-w-0">
              <h1 className="text-white font-semibold text-lg truncate">{detail?.title || '播放'}</h1>
              <div className="flex items-center gap-2 text-white/60 text-sm">
                <span>{currentSource?.name}</span>
                <span className="w-1 h-1 rounded-full bg-white/40" />
                <span>第 {activeEp + 1} 集</span>
                {quality !== 'auto' && (
                  <>
                    <span className="w-1 h-1 rounded-full bg-white/40" />
                    <span className="text-gold text-xs px-1.5 py-0.5 bg-gold/20 rounded">{quality}</span>
                  </>
                )}
              </div>
            </div>

            {/* 选集按钮 */}
            <button onClick={() => setShowEpisodes(!showEpisodes)}
              className="px-4 py-2 rounded-full bg-white/10 backdrop-blur text-white text-sm hover:bg-white/20 transition-colors flex items-center gap-2">
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
              </svg>
              选集
            </button>
          </div>
        </div>
      </div>

      {/* 中央播放按钮 */}
      <div className={`absolute inset-0 flex items-center justify-center z-20 transition-all duration-300 ${showControls && !isPlaying ? 'opacity-100 scale-100' : 'opacity-0 scale-90 pointer-events-none'}`}>
        <button onClick={togglePlay} 
          className="w-20 h-20 rounded-full bg-gold/95 flex items-center justify-center hover:bg-gold hover:scale-110 transition-all shadow-2xl shadow-gold/30">
          <svg className="w-10 h-10 text-black ml-1" fill="currentColor" viewBox="0 0 24 24">
            <path d="M8 5v14l11-7z" />
          </svg>
        </button>
      </div>

      {/* 底部控制栏 */}
      <div className={`absolute bottom-0 left-0 right-0 z-30 transition-all duration-500 ${showControls ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-full'}`}>
        <div className="bg-gradient-to-t from-black via-black/80 to-transparent pt-16 pb-6 px-6">
          
          {/* 进度条 */}
          <div className="relative h-1.5 mb-6 group cursor-pointer"
            onMouseDown={(e) => {
              setIsDragging(true)
              const rect = e.currentTarget.getBoundingClientRect()
              seekTo((e.clientX - rect.left) / rect.width)
            }}
            onMouseMove={(e) => {
              if (isDragging) {
                const rect = e.currentTarget.getBoundingClientRect()
                seekTo((e.clientX - rect.left) / rect.width)
              }
            }}
            onMouseUp={() => setIsDragging(false)}
            onMouseLeave={() => setIsDragging(false)}>
            
            {/* 背景轨道 */}
            <div className="absolute inset-0 bg-white/20 rounded-full" />
            
            {/* 缓冲进度 */}
            <div className="absolute h-full bg-white/30 rounded-full transition-all" 
              style={{ width: `${bufferedPercent}%` }} />
            
            {/* 播放进度 */}
            <div className="absolute h-full bg-gold rounded-full transition-all"
              style={{ width: `${progressPercent}%` }} />
            
            {/* 拖动圆点 */}
            <div className="absolute top-1/2 -translate-y-1/2 w-4 h-4 bg-gold rounded-full shadow-lg opacity-0 group-hover:opacity-100 transition-all hover:scale-125"
              style={{ left: `calc(${progressPercent}% - 8px)` }} />
          </div>

          {/* 控制按钮行 */}
          <div className="flex items-center gap-4">
            {/* 播放/暂停 */}
            <button onClick={togglePlay} 
              className="w-10 h-10 rounded-full bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors">
              {isPlaying ? (
                <svg className="w-5 h-5 text-white" fill="currentColor" viewBox="0 0 24 24">
                  <path d="M6 4h4v16H6V4zm8 0h4v16h-4V4z" />
                </svg>
              ) : (
                <svg className="w-5 h-5 text-white ml-0.5" fill="currentColor" viewBox="0 0 24 24">
                  <path d="M8 5v14l11-7z" />
                </svg>
              )}
            </button>

            {/* 上一集/下一集 */}
            <button onClick={prevEp} disabled={activeEp === 0}
              className="w-8 h-8 flex items-center justify-center text-white/60 hover:text-white disabled:opacity-30 transition-colors">
              <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                <path d="M6 6h2v12H6zm3.5 6l8.5 6V6z" />
              </svg>
            </button>
            <button onClick={nextEp} disabled={activeEp >= (currentSource?.urls?.length || 0) - 1}
              className="w-8 h-8 flex items-center justify-center text-white/60 hover:text-white disabled:opacity-30 transition-colors">
              <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                <path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z" />
              </svg>
            </button>

            {/* 时间显示 */}
            <div className="text-white/90 text-sm font-medium tabular-nums">
              {formatTime(currentTime)}
              <span className="text-white/40 mx-1">/</span>
              <span className="text-white/60">{formatTime(duration)}</span>
            </div>

            <div className="flex-1" />

            {/* 音量控制 */}
            <div className="flex items-center gap-2 group">
              <button onClick={() => changeVolume(isMuted ? 1 : 0)} 
                className="w-8 h-8 flex items-center justify-center text-white/70 hover:text-white transition-colors">
                {isMuted || volume === 0 ? (
                  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5.586 15H4a1 1 0 01-1-1v-4a1 1 0 011-1h1.586l4.707-4.707C10.923 3.663 12 4.109 12 5v14c0 .891-1.077 1.337-1.707.707L5.586 15z" />
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17 14l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2" />
                  </svg>
                ) : (
                  <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15.536 8.464a5 5 0 010 7.072m2.828-9.9a9 9 0 010 12.728M5.586 15H4a1 1 0 01-1-1v-4a1 1 0 011-1h1.586l4.707-4.707C10.923 3.663 12 4.109 12 5v14c0 .891-1.077 1.337-1.707.707L5.586 15z" />
                  </svg>
                )}
              </button>
              <div className="w-0 overflow-hidden group-hover:w-20 transition-all duration-300">
                <input type="range" min="0" max="1" step="0.05" value={isMuted ? 0 : volume}
                  onChange={(e) => changeVolume(parseFloat(e.target.value))}
                  className="w-20 h-1 bg-white/30 rounded-full appearance-none cursor-pointer" />
              </div>
            </div>

            {/* 清晰度选择 */}
            {availableLevels.length > 1 && (
              <div className="relative group">
                <button className="px-3 py-1.5 rounded-lg bg-white/10 text-white text-sm hover:bg-white/20 transition-colors min-w-[60px]">
                  {manualQuality < 0 ? '自动' : `${availableLevels[manualQuality]?.height || quality}p`}
                </button>
                <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 opacity-0 group-hover:opacity-100 pointer-events-none hover:pointer-events-auto transition-opacity">
                  <div className="bg-black/90 backdrop-blur rounded-lg p-1 shadow-xl min-w-[80px]">
                    <button onClick={() => {
                      setManualQuality(-1)
                      if (hlsRef.current) hlsRef.current.currentLevel = -1
                    }} className={`block w-full px-4 py-2 text-sm rounded text-left transition-colors ${manualQuality < 0 ? 'text-gold bg-gold/10' : 'text-white hover:bg-white/10'}`}>
                      自动
                    </button>
                    {availableLevels.map((level, idx) => (
                      <button key={idx} onClick={() => {
                        setManualQuality(idx)
                        if (hlsRef.current) hlsRef.current.currentLevel = idx
                      }} className={`block w-full px-4 py-2 text-sm rounded text-left transition-colors ${manualQuality === idx ? 'text-gold bg-gold/10' : 'text-white hover:bg-white/10'}`}>
                        {level.height}p
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            )}

            {/* 倍速 */}
            <div className="relative group">
              <button className="px-3 py-1.5 rounded-lg bg-white/10 text-white text-sm hover:bg-white/20 transition-colors">
                {speed}x
              </button>
              <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 opacity-0 group-hover:opacity-0 hover:opacity-100 pointer-events-none hover:pointer-events-auto transition-opacity">
                <div className="bg-black/90 backdrop-blur rounded-lg p-1 shadow-xl">
                  {[0.5, 1, 1.25, 1.5, 2].map(s => (
                    <button key={s} onClick={() => changeSpeed(s)}
                      className={`block w-full px-4 py-2 text-sm rounded text-left transition-colors ${speed === s ? 'text-gold bg-gold/10' : 'text-white hover:bg-white/10'}`}>
                      {s}x
                    </button>
                  ))}
                </div>
              </div>
            </div>

            {/* 全屏 */}
            <button onClick={toggleFullscreen} 
              className="w-10 h-10 rounded-full bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors">
              {isFullscreen ? (
                <svg className="w-5 h-5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              ) : (
                <svg className="w-5 h-5 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 8V4m0 0h4M4 4l5 5m11-1V4m0 0h-4m4 0l-5 5M4 16v4m0 0h4m-4 0l5-5m11 5l-5-5m5 5v-4m0 4h-4" />
                </svg>
              )}
            </button>
          </div>
        </div>
      </div>

      {/* 选集面板 */}
      <div className={`absolute top-20 right-6 bottom-32 w-72 bg-black/90 backdrop-blur-xl rounded-2xl overflow-hidden transition-all duration-300 z-40 ${showEpisodes ? 'opacity-100 translate-x-0' : 'opacity-0 translate-x-8 pointer-events-none'}`}>
        <div className="p-4 border-b border-white/10">
          <div className="flex items-center justify-between">
            <h3 className="text-white font-medium">选集</h3>
            <button onClick={() => setShowEpisodes(false)} className="text-white/50 hover:text-white">
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
        </div>
        <div className="overflow-y-auto h-full pb-20">
          {detail?.plays?.map((source, si) => (
            <div key={si} className="p-4 border-b border-white/5">
              <div className="text-white/50 text-xs uppercase tracking-wider mb-3">{source.name}</div>
              <div className="grid grid-cols-5 gap-2">
                {source.urls?.map((ep, ei) => {
                  const isActive = si === activeSource && ei === activeEp
                  return (
                    <button key={ei} onClick={() => { playFrom(si, ei, ep.url); setShowEpisodes(false); }}
                      className={`aspect-square rounded-lg text-sm font-medium transition-all ${
                        isActive
                          ? 'bg-gold text-black shadow-lg shadow-gold/30'
                          : 'bg-white/5 text-white/70 hover:bg-white/10 hover:text-white'
                      }`}>
                      {ep.episode || ei + 1}
                    </button>
                  )
                })}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* 点击遮罩关闭选集 */}
      {showEpisodes && (
        <div className="absolute inset-0 z-30" onClick={() => setShowEpisodes(false)} />
      )}
    </div>
  )
}
