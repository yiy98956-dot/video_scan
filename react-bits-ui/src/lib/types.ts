// ── Java API 返回的原始视频项 ──
export interface VideoItemVO {
  cmsVideoId: number
  source: string
  localId: number
  title: string
  coverUrl: string
  year: number
  area: string
  genre: string
  type: string
  score: string
  remark: string
  description: string
  director: string
  actors: string
  playCount: number
  likeCount: number
  collectCount: number
  commentCount: number
}

// ── 详情 ──
export interface PlayUrl {
  episode: string
  url: string
}
export interface PlayGroup {
  name: string
  from: string
  urls: PlayUrl[]
}
export interface VideoDetailVO extends VideoItemVO {
  playUrl: string
  rawPlayUrl: string
  plays: PlayGroup[]
  progress?: number
  liked?: boolean
  favorited?: boolean
}

// ── 分页响应 ──
export interface PageData<T> {
  items: T[]
  total: number
  page: number
  size: number
  totalPages: number
}

// ── 分类 ──
export interface CategoryNode {
  id: number
  name: string
  count: number
  children?: CategoryNode[]
}

// ── 搜索 ──
export interface SearchResult {
  items: VideoItemVO[]
  total: number
  page: number
  size: number
}

// ── 浏览筛选参数 ──
export interface BrowseParams {
  page?: number
  size?: number
  category?: string
  sort?: string
  type?: string
  year?: number
  area?: string
}
