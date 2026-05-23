// Package server — HTTP 路由注册（所有 API 端点）
package server

import (
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"crypto/tls"

	"server.app/cache"
	"server.app/category"
	"server.app/collector"
	"server.app/monitor"
	"server.app/model"
	"server.app/search"
)

type Server struct {
	Cache      *cache.Cache
	Collector  *collector.Collector
	Search     *search.Engine
	Monitor    *monitor.Monitor
	Sources    []collector.SourceInfo
	LiveGroups []LiveGroup
	DataDir    string
}

type LiveGroup struct {
	Name     string              `json:"name"`
	Channels []model.LiveChannel `json:"channels"`
}

func (s *Server) RegisterRoutes(mux *http.ServeMux) {
	// 根 — 管理面板
	mux.HandleFunc("/", s.handleRoot)

	// 状态
	mux.HandleFunc("/api/status", s.track("/api/status", s.handleStatus))
	mux.HandleFunc("/api/stats", s.track("/api/stats", s.handleStatus))
	mux.HandleFunc("/api/health", s.track("/api/health", s.handleHealth))
	mux.HandleFunc("/api/metrics", s.track("/api/metrics", s.handleMetrics))
	mux.HandleFunc("/api/cache", s.track("/api/cache", s.handleCache))
	mux.HandleFunc("/api/monitor", s.track("/api/monitor", s.handleMonitor))
	mux.HandleFunc("/api/monitor/endpoints", s.track("/api/monitor/endpoints", s.handleEndpoints))

	// 电影
	mux.HandleFunc("/api/movies/proxy", s.track("/api/movies/proxy", s.handleProxy))
	mux.HandleFunc("/api/movies/play", s.track("/api/movies/play", s.handlePlay))
	mux.HandleFunc("/api/movies/page", s.track("/api/movies/page", s.cacheWrap(s.handlePage, 60)))
	mux.HandleFunc("/api/movies/genres", s.track("/api/movies/genres", s.handleGenres))
	mux.HandleFunc("/api/movies/sources", s.track("/api/movies/sources", s.handleSources))
	mux.HandleFunc("/api/movies/detail", s.track("/api/movies/detail", s.cacheWrap(s.handleDetail, 300)))
	mux.HandleFunc("/api/movies/search", s.track("/api/movies/search", s.cacheWrap(s.handleSearch, 60)))
	mux.HandleFunc("/api/movies/search_advanced", s.track("/api/movies/search_advanced", s.handleSearchAdvanced))
	mux.HandleFunc("/api/movies/autocomplete", s.track("/api/movies/autocomplete", s.handleAutocomplete))
	mux.HandleFunc("/api/movies/query", s.track("/api/movies/query", s.handleQuery))
	mux.HandleFunc("/api/movies/updates", s.track("/api/movies/updates", s.handleUpdates))
	mux.HandleFunc("/api/movies/category", s.track("/api/movies/category", s.handleCategory))
	mux.HandleFunc("/api/movies/types", s.track("/api/movies/types", s.handleTypes))

	// 分类 - tree/tree-with-counts 不缓存（用户操作后即时生效）
	mux.HandleFunc("/api/category/tree", s.track("/api/category/tree", s.handleCategoryTree))
	mux.HandleFunc("/api/category/tree-with-counts", s.track("/api/category/tree-with-counts", s.handleCategoryTreeWithCounts))
	mux.HandleFunc("/api/category/all", s.track("/api/category/all", s.handleCategoryAll))
	mux.HandleFunc("/api/category/subs", s.track("/api/category/subs", s.handleCategorySubs))
	mux.HandleFunc("/api/category/toggle", s.track("/api/category/toggle", s.handleCategoryToggle))
	mux.HandleFunc("/api/category/update", s.track("/api/category/update", s.handleCategoryUpdate))
	mux.HandleFunc("/api/category/add", s.track("/api/category/add", s.handleCategoryAdd))
	mux.HandleFunc("/api/category/delete", s.track("/api/category/delete", s.handleCategoryDelete))
	mux.HandleFunc("/api/category/match", s.track("/api/category/match", s.handleCategoryMatch))
	mux.HandleFunc("/api/category/stats", s.track("/api/category/stats", s.handleCategoryStats))
	mux.HandleFunc("/api/category/save", s.track("/api/category/save", s.handleCategorySave))

	// 采集
	mux.HandleFunc("/api/collect/status", s.track("/api/collect/status", s.handleCollectStatus))
	mux.HandleFunc("/api/collect/run", s.track("/api/collect/run", s.handleCollectRun))
	mux.HandleFunc("/api/collect/reset", s.track("/api/collect/reset", s.handleCollectReset))
	mux.HandleFunc("/api/collect/nightly_check", s.track("/api/collect/nightly_check", s.handleNightlyCheck))

	// 直播
	mux.HandleFunc("/api/live", s.track("/api/live", s.handleLive))
	mux.HandleFunc("/api/live/groups", s.track("/api/live/groups", s.handleLiveGroups))
	mux.HandleFunc("/api/live/proxy", s.track("/api/live/proxy", s.handleLiveProxy))
	mux.HandleFunc("/api/live/play", s.track("/api/live/play", s.handleLivePlay))
}

func (s *Server) track(name string, h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		s.Monitor.OnEndpointCall(name, r.ContentLength)
		h(w, r)
	}
}

// ─── 路由处理 ───

// cacheWrap 为 handler 添加缓存层，key 自动为 "api:" + r.URL.RequestURI()
// ttlSeconds: 缓存过期秒数
func (s *Server) cacheWrap(h http.HandlerFunc, ttlSeconds int) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if s.Cache == nil {
			h(w, r)
			return
		}
		key := "api:" + r.URL.RequestURI()

		// 尝试从缓存读取
		if data := s.Cache.GetTTL(key); data != nil {
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			w.Header().Set("X-Cache", "HIT")
			w.Write(data)
			return
		}

		// 未命中 → 用 recorder 截获响应
		rec := &responseRecorder{ResponseWriter: w, buf: nil}
		h(rec, r)

		// 如果响应状态码为 200，写入缓存
		if rec.status == 200 && rec.buf != nil {
			s.Cache.PutTTL(key, rec.buf, ttlSeconds)
		}
	}
}

// responseRecorder 截获 http.ResponseWriter 的响应体
type responseRecorder struct {
	http.ResponseWriter
	buf    []byte
	status int
}

func (r *responseRecorder) Write(data []byte) (int, error) {
	if r.buf == nil {
		r.buf = make([]byte, 0, len(data))
	}
	r.buf = append(r.buf, data...)
	return r.ResponseWriter.Write(data)
}

func (r *responseRecorder) WriteHeader(statusCode int) {
	r.status = statusCode
	r.ResponseWriter.WriteHeader(statusCode)
}

func (s *Server) handleRoot(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		writeJSON(w, http.StatusNotFound, map[string]string{"error": "not_found"})
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(200)
	w.Write([]byte(adminHTML))
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	snap := s.Monitor.Snapshot()
	if s.Cache != nil {
		cs := s.Cache.Stats()
		snap.CacheHits = cs.Hits
		snap.CacheMisses = cs.Misses
		snap.CacheEvictions = cs.Evictions
		total := cs.Hits + cs.Misses
		if total > 0 {
			snap.CacheHitRate = float64(cs.Hits) / float64(total) * 100
		}
	}
	writeJSON(w, 200, snap)
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]string{"status": "running", "version": "go-v1.0.0"})
}

func (s *Server) handleMetrics(w http.ResponseWriter, r *http.Request) {
	snap := s.Monitor.Snapshot()
	metrics := fmt.Sprintf(`# HELP player_requests_total Total requests
# TYPE player_requests_total counter
player_requests_total %d
# HELP player_cache_hit_rate Cache hit rate
# TYPE player_cache_hit_rate gauge
player_cache_hit_rate %.1f
# HELP player_active_users Active users
# TYPE player_active_users gauge
player_active_users %d
`, snap.TotalRequests, snap.CacheHitRate, snap.ActiveUsers)
	w.Header().Set("Content-Type", "text/plain")
	w.Write([]byte(metrics))
}

func (s *Server) handleCache(w http.ResponseWriter, r *http.Request) {
	if s.Cache == nil {
		writeJSON(w, 200, map[string]string{"error": "cache disabled"})
		return
	}
	writeJSON(w, 200, s.Cache.Stats())
}

func (s *Server) handleMonitor(w http.ResponseWriter, r *http.Request) {
	snap := s.Monitor.Snapshot()
	writeJSON(w, 200, snap)
}

func (s *Server) handleEndpoints(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, s.Monitor.Endpoints())
}

// ─── 视频代理 ───

var proxyClient = &http.Client{
	Timeout: 60 * time.Second,
	Transport: &http.Transport{
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
		MaxIdleConns:    20,
	},
}

func init() {
	jar, _ := cookiejar.New(nil)
	proxyClient.Jar = jar
}

func proxyFetch(url, ua, ref string) ([]byte, error) {
	req, _ := http.NewRequest("GET", url, nil)
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Referer", ref)
	req.Header.Set("Origin", ref)
	req.Header.Set("Accept", "*/*")
	req.Header.Set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
	req.Header.Set("Accept-Encoding", "identity")
	resp, err := proxyClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 100*1024*1024))
	if err != nil {
		return nil, err
	}
	// 非 2xx 也返回 body（某些 CDN 403 仍然返回内容）
	return data, nil
}

func (s *Server) handleProxy(w http.ResponseWriter, r *http.Request) {
	rawUrl := r.URL.Query().Get("url")
	if rawUrl == "" {
		http.Error(w, "ERROR: need ?url=", 400)
		return
	}
	slog.Info("[proxy]", "url", rawUrl, "host", urlBase(rawUrl))

	cacheKey := "proxy:" + rawUrl
	if s.Cache != nil {
		if data := s.Cache.Get(cacheKey); data != nil {
			w.Header().Set("Content-Type", detectMime(rawUrl))
			w.Header().Set("Access-Control-Allow-Origin", "*")
			w.Write(data)
			return
		}
	}

	ref := r.URL.Query().Get("ref")
	if ref == "" {
		ref = urlBase(rawUrl)
	}
	ua := r.URL.Query().Get("ua")
	if ua == "" {
		ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36"
	}

	// 重试 3 次，先试 iOS UA（YingHua CDN 只认 iOS）
	var data []byte
	var err error
	userAgents := []string{
		"Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
		ua,
		"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
	}

	for attempt := 0; attempt < 3; attempt++ {
		data, err = proxyFetch(rawUrl, userAgents[attempt%len(userAgents)], ref)
		if err == nil && len(data) > 0 {
			break
		}
		time.Sleep(500 * time.Millisecond)
	}

	if err != nil || len(data) == 0 {
		http.Error(w, "ERROR: fetch failed: "+err.Error(), 502)
		return
	}

	if s.Cache != nil && len(data) < 50*1024*1024 {
		s.Cache.Put(cacheKey, data)
	}

	// m3u8 重写
	if strings.Contains(rawUrl, ".m3u8") {
		data = rewriteM3U8(data, rawUrl)
	}

	w.Header().Set("Content-Type", detectMime(rawUrl))
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.Write(data)
}

func (s *Server) handlePlay(w http.ResponseWriter, r *http.Request) {
	rawUrl := r.URL.Query().Get("url")
	if rawUrl == "" {
		http.Error(w, "ERROR: need ?url=", 400)
		return
	}
	http.Redirect(w, r, rawUrl, 302)
}

func detectMime(url string) string {
	if strings.Contains(url, ".m3u8") || strings.Contains(url, ".m3u") {
		return "application/vnd.apple.mpegurl"
	}
	if strings.Contains(url, ".ts") {
		return "video/MP2T"
	}
	if strings.Contains(url, ".mp4") {
		return "video/mp4"
	}
	return "application/octet-stream"
}

// rewriteCoverURLs 将所有封面图 URL 改写为通过 Java proxy 访问
func rewriteCoverURLs(items []model.MovieInfo) {
	for i := range items {
		if items[i].CoverUrl != "" && !strings.HasPrefix(items[i].CoverUrl, "/api/") {
			items[i].CoverUrl = "/api/movies/proxy?url=" + url.QueryEscape(items[i].CoverUrl)
		}
	}
}

func rewriteCoverDetail(m *model.MovieInfo) {
	if m == nil { return }
	if m.CoverUrl != "" && !strings.HasPrefix(m.CoverUrl, "/api/") {
		m.CoverUrl = "/api/movies/proxy?url=" + url.QueryEscape(m.CoverUrl)
	}
}

func urlBase(rawUrl string) string {
	if p := strings.Index(rawUrl, "://"); p >= 0 {
		hs := p + 3
		if sl := strings.Index(rawUrl[hs:], "/"); sl >= 0 {
			return rawUrl[:hs+sl+1]
		}
		return rawUrl + "/"
	}
	return ""
}

func rewriteM3U8(data []byte, originalUrl string) []byte {
	base := urlBase(originalUrl)
	lines := strings.Split(string(data), "\n")
	var result []string
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || trimmed[0] == '#' {
			result = append(result, line)
			continue
		}
		var absUrl string
		if strings.Contains(trimmed, "://") {
			absUrl = trimmed
		} else if trimmed[0] == '/' {
			absUrl = urlHostBase(originalUrl) + trimmed[1:]
		} else {
			absUrl = base + trimmed
		}
		result = append(result, "/api/movies/proxy?url="+url.QueryEscape(absUrl))
	}
	return []byte(strings.Join(result, "\n"))
}

func urlHostBase(rawUrl string) string {
	if p := strings.Index(rawUrl, "://"); p >= 0 {
		hs := p + 3
		if sl := strings.Index(rawUrl[hs:], "/"); sl >= 0 {
			return rawUrl[:hs+sl+1]
		}
		return rawUrl + "/"
	}
	return ""
}

// ─── 电影 ───

func (s *Server) handlePage(w http.ResponseWriter, r *http.Request) {
	pg := getIntParam(r, "pg", 1)
	size := getIntParam(r, "size", 40)
	sort := r.URL.Query().Get("sort")
	year := getIntParam(r, "year", 0)
	area := r.URL.Query().Get("area")
	genre := r.URL.Query().Get("genre")
	typeFilter := r.URL.Query().Get("type")
	// 兼容前端 category 参数名
	if genre == "" {
		genre = r.URL.Query().Get("category")
	}
	result := s.Collector.MoviesByGenre(genre, pg, size, sort, year, area, typeFilter)
	rewriteCoverURLs(result.Items)
	writeJSON(w, 200, result)
}

func (s *Server) handleGenres(w http.ResponseWriter, r *http.Request) {
	typeFilter := r.URL.Query().Get("type")
	if typeFilter != "" {
		genres := s.Collector.GenresByType(typeFilter)
		writeJSON(w, 200, genres)
	} else {
		genres := s.Collector.Genres()
		writeJSON(w, 200, genres)
	}
}

func (s *Server) handleSources(w http.ResponseWriter, r *http.Request) {
	srcs := s.Collector.Sources()
	writeJSON(w, 200, srcs)
}

func (s *Server) handleTypes(w http.ResponseWriter, r *http.Request) {
	types := s.Collector.Types()
	writeJSON(w, 200, types)
}

func (s *Server) handleDetail(w http.ResponseWriter, r *http.Request) {
	idStr := r.URL.Query().Get("id")
	source := r.URL.Query().Get("source")
	id, _ := strconv.Atoi(idStr)
	if id == 0 {
		writeJSON(w, 200, map[string]string{"error": "need ?id="})
		return
	}

	// 指定源 — 单源返回
	if source != "" {
		if m := s.Collector.GetMergedDetail(source, id); m != nil {
			rewriteCoverDetail(m)
			writeJSON(w, 200, m)
			return
		}
		writeJSON(w, 200, map[string]string{"error": "not_found"})
		return
	}

	// 未指定源 — 先找到第一个有该 id 的源，获取标题+年份
	// 再按标题+年份跨源匹配（不同源 vod_id 不对应同一部影片）
	var baseMovie *model.MovieInfo
	var baseSource string
	for _, src := range s.Sources {
		if m := s.Collector.GetMergedDetail(src.Name, id); m != nil {
			baseMovie = m
			baseSource = src.Name
			break
		}
	}
	if baseMovie == nil {
		writeJSON(w, 200, map[string]string{"error": "not_found"})
		return
	}

	// 按标题+年份跨源查找（排除已找到的源）
	title := baseMovie.Title
	year := baseMovie.Year
	matches, _ := s.Collector.FindMovieByTitleYear(title, year, baseSource)

	// 组装结果：基础信息来自 baseMovie，plays 从所有匹配源合并
	merged := &model.MovieInfo{
		VodId: baseMovie.VodId, Title: baseMovie.Title,
		CoverUrl: baseMovie.CoverUrl, Type: baseMovie.Type,
		Genre: baseMovie.Genre, Area: baseMovie.Area, Year: baseMovie.Year,
		Score: baseMovie.Score, Director: baseMovie.Director,
		Actors: baseMovie.Actors,
		Description: baseMovie.Description, Remark: baseMovie.Remark,
		Source: baseMovie.Source, ListDate: baseMovie.ListDate,
	}
	baseType := baseMovie.Type

	// 合并 plays（baseMovie 的 plays 先加入）
	seenPlayKeys := make(map[string]bool)
	for _, pg := range baseMovie.Plays {
		name := pg.Name
		if name == "" { name = pg.From }
		if name != "" {
			seenPlayKeys[baseSource+"::"+name] = true
			merged.Plays = append(merged.Plays, pg)
		}
	}

	// 合并匹配源的 plays（同类型才合并）
	for _, match := range matches {
		m := s.Collector.GetMergedDetail(match.Source, match.VodId)
		if m == nil { continue }
		// 类型不同不合并
		if baseType != "" && m.Type != "" && m.Type != baseType { continue }
		for _, pg := range m.Plays {
			name := pg.Name
			if name == "" { name = pg.From }
			if name == "" { continue }
			uniqKey := match.Source + "::" + name
			if !seenPlayKeys[uniqKey] {
				seenPlayKeys[uniqKey] = true
				merged.Plays = append(merged.Plays, pg)
			}
		}
	}

	rewriteCoverDetail(merged)
	// 详情返回前做质量检测（假多集→电影）
	collector.FixMovieType(merged)
	writeJSON(w, 200, merged)
}

func (s *Server) handleSearch(w http.ResponseWriter, r *http.Request) {
	q := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("q")))
	if q == "" {
		writeJSON(w, 200, map[string]any{"items": []model.MovieInfo{}, "total": 0})
		return
	}

	movies := s.Collector.AllMovies()
	var results []model.MovieInfo
	for _, mi := range movies {
		if len(results) >= 40 {
			break
		}
		if strings.Contains(strings.ToLower(mi.Title), q) {
			// 搜索用 GetMovie 读取完整数据（含 plays），确保分类准确
			s.Collector.ValidateMovieQuality(&mi)
			results = append(results, mi.ToMovieInfo())
		}
	}
	rewriteCoverURLs(results)
	writeJSON(w, 200, map[string]any{"items": results, "total": len(results)})
}

func (s *Server) handleSearchAdvanced(w http.ResponseWriter, r *http.Request) {
	q := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("q")))
	filters := make(map[string]string)
	if v := r.URL.Query().Get("source"); v != "" {
		filters["source"] = v
	}
	if v := r.URL.Query().Get("genre"); v != "" {
		filters["genre"] = v
	}
	if v := r.URL.Query().Get("type"); v != "" {
		filters["type"] = v
	}
	pg := getIntParam(r, "pg", 1)
	sz := getIntParam(r, "size", 40)

	movies := s.Collector.AllMovies()
	var matched []model.MovieInfo
	for _, mi := range movies {
		if q != "" && !strings.Contains(strings.ToLower(mi.Title), q) {
			continue
		}
		if v, ok := filters["source"]; ok && mi.Source != v {
			continue
		}
		if v, ok := filters["genre"]; ok && !strings.Contains(mi.Genre, v) {
			continue
		}
		if v, ok := filters["type"]; ok && mi.Type != v {
			continue
		}
		if m := s.Collector.GetMovie(mi.Source, mi.VodId); m != nil {
			matched = append(matched, *m)
		}
	}

	// 分页
	total := len(matched)
	start := (pg - 1) * sz
	var page []model.MovieInfo
	if start < total {
		end := start + sz
		if end > total {
			end = total
		}
		page = matched[start:end]
	}
	writeJSON(w, 200, map[string]any{"results": page, "total": total})
}

func (s *Server) handleAutocomplete(w http.ResponseWriter, r *http.Request) {
	q := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("q")))
	limit := getIntParam(r, "size", 10)

	movies := s.Collector.AllMovies()
	seen := make(map[string]bool)
	var suggestions []string
	for _, mi := range movies {
		title := strings.ToLower(mi.Title)
		if strings.HasPrefix(title, q) && !seen[mi.Title] {
			suggestions = append(suggestions, mi.Title)
			seen[mi.Title] = true
			if len(suggestions) >= limit {
				break
			}
		}
	}
	writeJSON(w, 200, suggestions)
}

func (s *Server) handleQuery(w http.ResponseWriter, r *http.Request) {
	genres := s.Collector.Genres()
	writeJSON(w, 200, genres)
}

func (s *Server) handleUpdates(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]int{"update_count": 0})
}

func (s *Server) handleCategory(w http.ResponseWriter, r *http.Request) {
	g := r.URL.Query().Get("g")
	pg := getIntParam(r, "pg", 1)
	sz := getIntParam(r, "size", 40)
	sort := r.URL.Query().Get("sort")
	year := getIntParam(r, "year", 0)
	area := r.URL.Query().Get("area")
	typeFilter := r.URL.Query().Get("type")

	// 判断 g 是否为一级分类名 → 当 type 过滤；否则当 genre 过滤
	tree := category.GetTree()
	isTop := false
	for _, c := range tree {
		if c.Name == g {
			isTop = true
			break
		}
	}

	var genre string
	if isTop {
		typeFilter = g
	} else {
		genre = g
	}

	result := s.Collector.MoviesByGenre(genre, pg, sz, sort, year, area, typeFilter)
	rewriteCoverURLs(result.Items)
	writeJSON(w, 200, result)
}

// ═══════════════════════════════════════════════════════════
// 新分类系统 API — 一二级分类树 + 管理
// ═══════════════════════════════════════════════════════════

// handleCategoryTree 获取可见分类树（前端导航用）
func (s *Server) handleCategoryTree(w http.ResponseWriter, r *http.Request) {
	tree := category.GetTree()
	writeJSON(w, 200, tree)
}

// handleCategoryAll 获取全量分类（管理后台用）
func (s *Server) handleCategoryAll(w http.ResponseWriter, r *http.Request) {
	all := category.GetAll()
	writeJSON(w, 200, all)
}

// handleCategorySubs 获取指定一级下的二级分类
func (s *Server) handleCategorySubs(w http.ResponseWriter, r *http.Request) {
	pid := getIntParam(r, "pid", 0)
	if pid == 0 {
		writeJSON(w, 200, []int{})
		return
	}
	subs := category.GetSubs(pid)
	writeJSON(w, 200, subs)
}

// handleCategoryToggle 切换分类显示/隐藏
func (s *Server) handleCategoryToggle(w http.ResponseWriter, r *http.Request) {
	id := getIntParam(r, "id", 0)
	if id == 0 {
		writeJSON(w, 200, map[string]any{"error": "need ?id="})
		return
	}
	show := category.ToggleShow(id)
	if s.Cache != nil {
		s.Cache.Clear()
	}
	writeJSON(w, 200, map[string]any{"id": id, "is_show": show})
}

// handleCategoryUpdate 更新分类（名称/别名/排序/显示）
func (s *Server) handleCategoryUpdate(w http.ResponseWriter, r *http.Request) {
	var body struct {
		ID     int    `json:"id"`
		Name   string `json:"name"`
		Alias  string `json:"alias"`
		Sort   int    `json:"sort"`
		IsShow *bool  `json:"is_show"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.ID == 0 {
		writeJSON(w, 200, map[string]string{"error": "invalid body"})
		return
	}
	isShow := true
	if body.IsShow != nil {
		isShow = *body.IsShow
	}
	category.Update(body.ID, body.Name, body.Alias, body.Sort, isShow)
	writeJSON(w, 200, map[string]string{"status": "ok"})
}

// handleCategoryAdd 添加二级子分类
func (s *Server) handleCategoryAdd(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Pid   int    `json:"pid"`
		Name  string `json:"name"`
		Alias string `json:"alias"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.Pid == 0 || body.Name == "" {
		writeJSON(w, 200, map[string]string{"error": "need pid + name"})
		return
	}
	sub := category.AddSub(body.Pid, body.Name, body.Alias)
	writeJSON(w, 200, sub)
}

// handleCategoryDelete 删除二级子分类
func (s *Server) handleCategoryDelete(w http.ResponseWriter, r *http.Request) {
	id := getIntParam(r, "id", 0)
	if id == 0 {
		writeJSON(w, 200, map[string]string{"error": "need ?id="})
		return
	}
	category.DeleteSub(id)
	writeJSON(w, 200, map[string]string{"status": "deleted"})
}

// handleCategoryMatch 采集关键词分类匹配测试
func (s *Server) handleCategoryMatch(w http.ResponseWriter, r *http.Request) {
	title := r.URL.Query().Get("title")
	genre := r.URL.Query().Get("genre")
	catType := r.URL.Query().Get("type")
	catID, subID := category.MatchCategory(title, genre, catType)
	primary := category.GetByID(catID)
	var sub *category.Category
	if subID > 0 {
		sub = category.GetByID(subID)
	}
	result := map[string]any{
		"cat_id":   catID,
		"cat_name": "",
		"sub_id":   subID,
		"sub_name": "",
	}
	if primary != nil {
		result["cat_name"] = primary.Name
	}
	if sub != nil {
		result["sub_name"] = sub.Name
	}
	writeJSON(w, 200, result)
}

// handleCategoryStats 返回当前分类分布统计
func (s *Server) handleCategoryStats(w http.ResponseWriter, r *http.Request) {
	stats := s.Collector.TypeStats()
	tree := category.GetTree()
	writeJSON(w, 200, map[string]any{
		"type_stats": stats,
		"tree":       tree,
	})
}

func (s *Server) handleCategorySave(w http.ResponseWriter, r *http.Request) {
	if err := category.Save(); err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]string{"status": "ok"})
}

// handleCategoryTreeWithCounts 返回带影片数量的分类树
// 一级分类按 type 统计，二级分类按 genre 统计（容错匹配）
// ?showHidden=1 时包含隐藏分类（管理用）
func (s *Server) handleCategoryTreeWithCounts(w http.ResponseWriter, r *http.Request) {
	showHidden := r.URL.Query().Get("showHidden") == "1"

	var tree []category.TreeJSON
	if showHidden {
		tree = category.GetTreeAll()
	} else {
		tree = category.GetTree()
	}

	catCounts := make(map[string]int)           // catName → count
	subCounts := make(map[int]map[string]int)   // catID → (subName → count)

	// 容错匹配
	type subInfo struct{ pid int; name string }
	subLookup := make(map[string]subInfo)
	for _, c := range tree {
		for _, s := range c.Subs {
			subLookup[s.Name] = subInfo{pid: c.ID, name: s.Name}
			subLookup[s.Name+"片"] = subInfo{pid: c.ID, name: s.Name}
			if s.Alias != "" {
				subLookup[s.Alias] = subInfo{pid: c.ID, name: s.Name}
			}
		}
	}

	hiddenGenres := category.HiddenGenres()
	hiddenTypes := category.HiddenTypes()

	s.Collector.WalkIndex(func(idx model.MovieIndex) {
		t := idx.Type
		if t == "" { t = "(空)" }
		// 普通模式跳过隐藏分类
		if !showHidden {
			if hiddenTypes[t] { return }
			if len(hiddenGenres) > 0 {
				for _, g := range strings.Split(idx.Genre, ",") {
					g = strings.TrimSpace(g)
					if hiddenGenres[g] { return }
				}
			}
		}
		catCounts[t]++

		// genre 可能用逗号/斜杠/句号/空格分隔
		parts := strings.FieldsFunc(idx.Genre, func(r rune) bool {
			return r == ',' || r == '/' || r == '。' || r == ' '
		})
		for _, p := range parts {
			g := strings.TrimSpace(p)
			g = strings.TrimRight(g, ". ")
			if g == "" { continue }
			if si, ok := subLookup[g]; ok {
				if subCounts[si.pid] == nil {
					subCounts[si.pid] = make(map[string]int)
				}
				subCounts[si.pid][si.name]++
			}
		}
	})

	type enrichedSub struct {
		ID    int    `json:"id"`
		Name  string `json:"name"`
		Count int    `json:"count"`
	}

	type enrichedCat struct {
		ID    int           `json:"id"`
		Name  string        `json:"name"`
		Alias string        `json:"alias"`
		Count int           `json:"count"`
		Subs  []enrichedSub `json:"subs,omitempty"`
	}

	var result []enrichedCat
	for _, c := range tree {
		count := catCounts[c.Name]
		var subs []enrichedSub
		for _, s := range c.Subs {
			cnt := 0
			if sc, ok := subCounts[c.ID]; ok {
				cnt = sc[s.Name]
			}
			subs = append(subs, enrichedSub{ID: s.ID, Name: s.Name, Count: cnt})
		}
		result = append(result, enrichedCat{
			ID: c.ID, Name: c.Name, Alias: c.Alias,
			Count: count, Subs: subs,
		})
	}

	writeJSON(w, 200, result)
}

// ─── 采集 ───

func (s *Server) handleCollectStatus(w http.ResponseWriter, r *http.Request) {
	stats := s.Collector.Stats()
	writeJSON(w, 200, stats)
}

func (s *Server) handleCollectRun(w http.ResponseWriter, r *http.Request) {
	go s.Collector.CollectAll(s.Sources)
	writeJSON(w, 200, map[string]string{"status": "started"})
}

func (s *Server) handleCollectReset(w http.ResponseWriter, r *http.Request) {
	go func() {
		// 先清空所有旧数据
		entries, _ := os.ReadDir(s.DataDir)
		for _, e := range entries {
			if e.IsDir() {
				os.RemoveAll(filepath.Join(s.DataDir, e.Name()))
			}
		}
		// 重建 collector + 清空续点 + 重新采集
		s.Collector = collector.NewCollector(s.DataDir)
		s.Collector.ResetCheckpoint()
		s.Collector.CollectAll(s.Sources)
	}()
	writeJSON(w, 200, map[string]string{"status": "started"})
}

func (s *Server) handleNightlyCheck(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, map[string]string{"status": "started"})
}

// ─── 直播 ───

func (s *Server) handleLive(w http.ResponseWriter, r *http.Request) {
	var channels []model.LiveChannel
	for _, g := range s.LiveGroups {
		channels = append(channels, g.Channels...)
	}
	writeJSON(w, 200, channels)
}

func (s *Server) handleLiveGroups(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, s.LiveGroups)
}

func (s *Server) handleLiveProxy(w http.ResponseWriter, r *http.Request) {
	rawUrl := r.URL.Query().Get("url")
	if rawUrl == "" {
		http.Error(w, "ERROR: need ?url=", 400)
		return
	}
	ref := r.URL.Query().Get("ref")

	req, _ := http.NewRequest("GET", rawUrl, nil)
	req.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
	req.Header.Set("Accept", "*/*")
	req.Header.Set("Origin", "https://live.hacks.tools")
	if ref != "" {
		req.Header.Set("Referer", ref)
	} else {
		req.Header.Set("Referer", "https://live.hacks.tools/")
	}
	resp, err := proxyClient.Do(req)
	if err != nil {
		http.Error(w, "ERROR: fetch failed: "+err.Error(), 502)
		return
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 50*1024*1024))
	if len(data) > 0 {
		ct := resp.Header.Get("Content-Type")
		if ct == "" {
			ct = detectMime(rawUrl)
		}
		w.Header().Set("Content-Type", ct)
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		w.Write(data)
	}
}

func (s *Server) handleLivePlay(w http.ResponseWriter, r *http.Request) {
	name := r.URL.Query().Get("name")
	if name == "" {
		http.Error(w, "ERROR: need ?name=", 400)
		return
	}
	for _, g := range s.LiveGroups {
		for _, ch := range g.Channels {
			if ch.Name == name {
				// 下载并重写 m3u8
				req, _ := http.NewRequest("GET", ch.Url, nil)
				if ch.Ref != "" {
					req.Header.Set("Referer", ch.Ref)
				}
				ua := ch.Ua
				if ua == "" {
					ua = "Mozilla/5.0"
				}
				req.Header.Set("User-Agent", ua)
				resp, err := proxyClient.Do(req)
				if err != nil {
					http.Error(w, "ERROR: fetch failed", 502)
					return
				}
				defer resp.Body.Close()
				data, _ := io.ReadAll(resp.Body)
				if len(data) == 0 {
					http.Error(w, "ERROR: empty", 502)
					return
				}
				// 重写相对路径
				baseUrl := urlBase(ch.Url)
				lines := strings.Split(string(data), "\n")
				var result []string
				for _, line := range lines {
					trimmed := strings.TrimSpace(line)
					if trimmed == "" || trimmed[0] == '#' {
						result = append(result, line)
					} else {
						var absUrl string
						if strings.Contains(trimmed, "://") {
							absUrl = trimmed
						} else if trimmed[0] == '/' {
							absUrl = urlHostBase(ch.Url) + trimmed[1:]
						} else {
							absUrl = baseUrl + trimmed
						}
						result = append(result, "/api/live/proxy?url="+url.QueryEscape(absUrl))
						if ch.Ref != "" {
							result[len(result)-1] += "&ref=" + url.QueryEscape(ch.Ref)
						}
					}
				}
				w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
				w.Write([]byte(strings.Join(result, "\n")))
				return
			}
		}
	}
	http.Error(w, "ERROR: channel not found", 404)
}

// ─── 工具 ───

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Access-Control-Allow-Origin", "*")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func getIntParam(r *http.Request, name string, def int) int {
	v := r.URL.Query().Get(name)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}

// 管理面板 HTML
var adminHTML = `<!DOCTYPE html><html lang=zh><meta charset=utf-8><title>PlayerServer</title>
<meta name=viewport content="width=device-width,initial-scale=1">
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Noto Sans SC',sans-serif;background:#0a0a0f;color:#e8e8f0;padding:20px;max-width:800px;margin:0 auto}
h1{font-size:18px;margin-bottom:16px;color:#00d4aa}
.card{background:#141420;border-radius:8px;padding:14px;margin-bottom:12px;border:1px solid #2a2a44;border-left:3px solid #00d4aa}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:8px}@media(max-width:500px){.grid{grid-template-columns:1fr}}
.stat{padding:8px 10px;background:#1c1c2e;border-radius:4px}
.stat .lbl{font-size:10px;color:#8888aa}.stat .val{font-size:14px;font-weight:600;margin-top:2px;color:#00d4aa}
.btn{padding:6px 16px;border-radius:4px;font-size:12px;cursor:pointer;background:#1c1c2e;color:#e8e8f0;border:1px solid #2a2a44;margin-right:6px;font-family:inherit}
.btn:disabled{opacity:.4;cursor:not-allowed}
</style></head><body>
<h1>Go-PlayerServer</h1>
<div class=card><h2>Status</h2><div class=grid id=statusGrid></div></div>
<div class=card><h2>Collection</h2><div class=grid id=collectGrid></div><div style=margin-top:10px><button class=btn onclick="fetch('/api/collect/run').then(()=>setTimeout(load,2000))">Start</button><button class=btn onclick="fetch('/api/collect/reset').then(()=>setTimeout(load,2000))">Reset</button></div></div>
<script>
async function load(){
  var s=await(await fetch('/api/status')).json();
  var col;try{col=await(await fetch('/api/collect/status')).json()}catch(e){col={}};
  document.getElementById('statusGrid').innerHTML='<div class=stat><div class=lbl>Uptime</div><div class=val>'+Math.floor(s.uptime_seconds/3600)+'h '+Math.floor((s.uptime_seconds%3600)/60)+'m</div></div><div class=stat><div class=lbl>Movies</div><div class=val>'+(col.total_movies||0)+'</div></div><div class=stat><div class=lbl>Requests</div><div class=val>'+(s.total_requests||0)+'</div></div><div class=stat><div class=lbl>Cache Hit</div><div class=val>'+(s.cache_hit_rate||0).toFixed(1)+'%</div></div>';
  document.getElementById('collectGrid').innerHTML='<div class=stat><div class=lbl>Status</div><div class=val>'+(col.collecting?'⏳':'✅')+'</div></div><div class=stat><div class=lbl>Movies</div><div class=val>'+(col.total_movies||0)+'</div></div><div class=stat><div class=lbl>Collected</div><div class=val>'+(col.collect_count||0)+'</div></div><div class=stat><div class=lbl>Last</div><div class=val style=font-size:11px>'+(col.last_collect||'-')+'</div></div>';
}
load();setInterval(load,5000);
</script>`
