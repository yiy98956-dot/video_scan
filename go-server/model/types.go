package model

import (
	"encoding/json"
	"strings"
)

// MovieInfo 影片信息 — 兼容 C++ JSON 字段名（id vs vod_id, coverUrl vs cover）
type MovieInfo struct {
	VodId       int         `json:"vod_id"`
	VodIdCompat int         `json:"id,omitempty"`  // C++ 兼容
	Title       string      `json:"title"`
	CoverUrl    string      `json:"coverUrl,omitempty"`
	CoverCompat string      `json:"cover,omitempty"` // C++ 兼容
	Year        int         `json:"year"`
	Area        string      `json:"area"`
	Genre       string      `json:"genre"`
	Director    string      `json:"director"`
	Actors      string      `json:"actors"`
	Description string      `json:"description"`
	Score       string      `json:"score"`
	Remark      string      `json:"remark"`
	Source      string      `json:"source"`
	Type        string      `json:"type"`
	RawType     string      `json:"raw_type,omitempty"` // 采集源原始 type_name
	ListDate    string      `json:"list_date"`
	Status      string      `json:"status"`
	LastCheck   string      `json:"lastCheckTime,omitempty"`
	HasUpdate   bool        `json:"hasUpdate"`
	Plays       []PlayGroup `json:"plays,omitempty"`
}

// FromIndex 从 MovieIndex 构建轻量 MovieInfo（不含 plays），避免磁盘读取
func (mi MovieIndex) ToMovieInfo() MovieInfo {
	return MovieInfo{
		VodId:       mi.VodId,
		Source:      mi.Source,
		Title:       mi.Title,
		CoverUrl:    mi.CoverUrl,
		Genre:       mi.Genre,
		Area:        mi.Area,
		Year:        mi.Year,
		Score:       mi.Score,
		Remark:      mi.Remark,
		Type:        mi.Type,
		RawType:     mi.RawType,
	}
}

// MovieIndex 轻量索引 — 内存中只存这个，不存完整 MovieInfo
type MovieIndex struct {
	VodId    int    `json:"vod_id"`
	Source   string `json:"source"`
	Title    string `json:"title"`
	CoverUrl string `json:"coverUrl,omitempty"`
	Genre    string `json:"genre"`
	Area     string `json:"area"`
	Year     int    `json:"year"`
	Score    string `json:"score"`
	Remark   string `json:"remark"`
	Type     string `json:"type"`
	RawType  string `json:"raw_type,omitempty"` // 采集源原始 type_name
	Actors   string `json:"actors,omitempty"`   // 演员（用于搜索）
	Director string `json:"director,omitempty"` // 导演（用于搜索）
}

// UnmarshalJSON 自定义反序列化 — 兼容 C++ 和 Go 两种字段名
func (m *MovieInfo) UnmarshalJSON(data []byte) error {
	// 先用 map 解析
	var raw map[string]any
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}

	// 标准 Go 方式
	type Alias MovieInfo
	var alias Alias
	if err := json.Unmarshal(data, &alias); err != nil {
		return err
	}
	*m = MovieInfo(alias)

	// C++ 兼容：从 map 中读取 alternative 字段名
	if m.VodId == 0 {
		if id, ok := getInt64(raw, "id"); ok {
			m.VodId = int(id)
		}
	}
	if m.CoverUrl == "" {
		if c, ok := getStr(raw, "coverUrl"); ok {
			m.CoverUrl = c
		} else if c, ok := getStr(raw, "cover"); ok {
			m.CoverUrl = c
		}
	}

	return nil
}

func (m MovieInfo) MarshalJSON() ([]byte, error) {
	type Alias MovieInfo
	// Set C++ compat fields so they appear in JSON output
	m.VodIdCompat = m.VodId
	m.CoverCompat = m.CoverUrl
	return json.Marshal(Alias(m))
}

func getInt64(m map[string]any, key string) (int64, bool) {
	v, ok := m[key]
	if !ok {
		return 0, false
	}
	switch val := v.(type) {
	case float64:
		return int64(val), true
	case int64:
		return val, true
	case json.Number:
		n, err := val.Int64()
		return n, err == nil
	}
	return 0, false
}

func getStr(m map[string]any, key string) (string, bool) {
	v, ok := m[key]
	if !ok {
		return "", false
	}
	s, ok := v.(string)
	return strings.TrimSpace(s), ok
}

type PlayGroup struct {
	From string    `json:"from"`
	Name string    `json:"name"`
	Urls []PlayUrl `json:"urls"`
}

type PlayUrl struct {
	Episode string `json:"episode"`
	Url     string `json:"url"`
}

type CmsSource struct {
	Name   string `json:"name"`
	Url    string `json:"url"`
	Pages  int    `json:"pages"`
	Active bool   `json:"active"`
}

type LiveChannel struct {
	Name  string `json:"name"`
	Url   string `json:"url"`
	Group string `json:"group"`
	Logo  string `json:"logo,omitempty"`
	Ref   string `json:"ref,omitempty"`
	Ua    string `json:"ua,omitempty"`
}

type CacheStats struct {
	MemoryItems  int64   `json:"memory_items"`
	MemoryBytes  int64   `json:"memory_bytes"`
	MemoryLimit  int64   `json:"memory_limit"`
	DiskItems    int64   `json:"disk_items"`
	DiskBytes    int64   `json:"disk_bytes"`
	DiskLimit    int64   `json:"disk_limit"`
	Hits         uint64  `json:"hits"`
	Misses       uint64  `json:"misses"`
	Evictions    uint64  `json:"evictions"`
	HitRate      float64 `json:"hit_rate"`
	DiskWrites   uint64  `json:"disk_writes"`
}

type ServerSnapshot struct {
	UptimeSeconds  uint64  `json:"uptime_seconds"`
	ActiveUsers    uint64  `json:"active_users"`
	TotalRequests  uint64  `json:"total_requests"`
	TotalBytesIn   uint64  `json:"total_bytes_in"`
	TotalBytesOut  uint64  `json:"total_bytes_out"`
	BytesInPerSec  float64 `json:"bytes_in_per_sec"`
	BytesOutPerSec float64 `json:"bytes_out_per_sec"`
	CacheHits      uint64  `json:"cache_hits"`
	CacheMisses    uint64  `json:"cache_misses"`
	CacheEvictions uint64  `json:"cache_evictions"`
	CacheHitRate   float64 `json:"cache_hit_rate"`
	MemoryLimitGB  int     `json:"memory_limit_gb"`
	MemoryUsedGB   float64 `json:"memory_used_gb"`
	DiskLimitGB    int     `json:"disk_limit_gb"`
	DiskUsedGB     float64 `json:"disk_used_gb"`
	Version        string  `json:"version"`
}

type TrafficPoint struct {
	BytesInPerSec  float64 `json:"bytes_in_per_sec"`
	BytesOutPerSec float64 `json:"bytes_out_per_sec"`
	ActiveUsers    uint64  `json:"active_users"`
	RequestCount   uint64  `json:"request_count"`
	CacheHitRate   float64 `json:"cache_hit_rate"`
	Timestamp      uint64  `json:"timestamp"`
}

type EndpointStat struct {
	Route    string `json:"route"`
	Count    uint64 `json:"count"`
	LastCall uint64 `json:"last_call_time"`
	BytesOut uint64 `json:"bytes_out"`
	Active   bool   `json:"active"`
}

type CollectStats struct {
	Collecting    bool   `json:"collecting"`
	TotalMovies   int    `json:"total_movies"`
	Failed        int    `json:"failed"`
	LastCollect   string `json:"last_collect"`
	Progress      int    `json:"progress"`
	ProgressText  string `json:"progress_text"`
	CollectCount  int    `json:"collect_count"`
	UpdateCount   int    `json:"update_count"`
}

type SearchResult struct {
	Rank  int       `json:"rank"`
	Movie MovieInfo `json:"movie"`
}

type FacetCounts struct {
	Sources []FacetItem `json:"sources"`
	Types   []FacetItem `json:"types"`
	Genres  []FacetItem `json:"genres"`
	Years   []FacetItem `json:"years"`
	Status  []FacetItem `json:"status"`
}

type FacetItem struct {
	Name  string `json:"n"`
	Count int    `json:"c"`
}

type PageResult struct {
	Items []MovieInfo `json:"items"`
	Page  int         `json:"page"`
	Size  int         `json:"size"`
	Total int         `json:"total"`
}
