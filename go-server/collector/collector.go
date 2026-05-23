package collector

import (
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode"

	"server.app/category"
	"server.app/model"
)

const (
	MemLimit        = 100
	DetailBatchSize = 50
	BatchDelay      = 10 * time.Millisecond
	Concurrency     = 10
	EmptyLimit      = 5
	DetailWorkers   = 8
	ListBytesLimit  = 30000
)

type Collector struct {
	mu          sync.RWMutex
	index       []model.MovieIndex
	indexMap    map[string]int
	dir         string
	httpClient  *http.Client
	collecting  atomic.Bool
	totalMovies int
	failed      int
	lastCollect string
	progress    int
	progressText string
	collectCnt  int
	updateCnt   int

	// 续点采集
	cpMu       sync.Mutex
	checkpoint map[string]int
}

type SourceInfo struct {
	Name   string `json:"name"`
	Url    string `json:"url"`
	Pages  int    `json:"pages"`
	Active bool   `json:"active"`
}

type SourceCount struct {
	Name  string `json:"name"`
	Count int    `json:"count"`
}

func NewCollector(dataDir string) *Collector {
	os.MkdirAll(dataDir, 0755)
	c := &Collector{
		index:      make([]model.MovieIndex, 0, 20000),
		indexMap:   make(map[string]int),
		dir:        dataDir,
		checkpoint: make(map[string]int),
		httpClient: &http.Client{Timeout: 60 * time.Second, Transport: &http.Transport{
			MaxIdleConns:        50,
			MaxIdleConnsPerHost: 10,
			IdleConnTimeout:     90 * time.Second,
		}},
	}
	c.loadCheckpoint()
	return c
}

func (c *Collector) moviePath(source string, vodId int) string {
	return filepath.Join(c.dir, source, fmt.Sprintf("%d", vodId/1000), fmt.Sprintf("%d.json", vodId))
}
func (c *Collector) movieIdxPath(source string, vodId int) string {
	return filepath.Join(c.dir, source, fmt.Sprintf("%d", vodId/1000), fmt.Sprintf("%d.idx", vodId))
}

func (c *Collector) AddMovie(m model.MovieInfo) {
	c.applyCategoryMapping(&m)
	FixMovieType(&m)  // 采集时检测：假多集改类型（内存+磁盘一起修）
	c.mu.Lock()
	key := fmt.Sprintf("%s:%d", m.Source, m.VodId)
	if idx, ok := c.indexMap[key]; ok {
		c.index[idx] = model.MovieIndex{
			VodId: m.VodId, Source: m.Source, Title: m.Title,
			Genre: m.Genre, Area: m.Area, Year: m.Year,
			Score: m.Score, Remark: m.Remark, Type: m.Type, RawType: m.RawType,
			Actors: m.Actors, Director: m.Director,
		}
		c.updateCnt++
	} else {
		c.indexMap[key] = len(c.index)
		c.index = append(c.index, model.MovieIndex{
			VodId: m.VodId, Source: m.Source, Title: m.Title,
			Genre: m.Genre, Area: m.Area, Year: m.Year,
			Score: m.Score, Remark: m.Remark, Type: m.Type, RawType: m.RawType,
			Actors: m.Actors, Director: m.Director,
		})
		c.collectCnt++
	}
	c.mu.Unlock()

	path := c.moviePath(m.Source, m.VodId)
	if _, err := os.Stat(path); os.IsNotExist(err) {
		c.saveMovie(m)
	} else if len(m.Plays) > 0 {
		c.saveMovie(m)
	}
}

func (c *Collector) applyCategoryMapping(m *model.MovieInfo) {
	// 使用分类详细.txt 6步精准分类逻辑
	bigType, smallType := category.Classify(m.RawType, m.Genre, m.Title)
	if bigType != "" {
		m.Type = bigType
	}
	if smallType != "" && smallType != "无细分" {
		// 只当 Genre 为空时才覆盖（保留已有子分类）
		if m.Genre == "" || m.Genre == m.Type {
			m.Genre = smallType
		}
	}
}

func (c *Collector) ReclassifyAll() {
	c.mu.Lock()
	defer c.mu.Unlock()
	count := 0
	for i, idx := range c.index {
		// 重新应用分类映射（使用新6步逻辑）
		bigType, _ := category.Classify(idx.RawType, idx.Genre, idx.Title)
		
		changed := false
		if bigType != "" && bigType != "其他" && bigType != idx.Type {
			idx.Type = bigType
			changed = true
		}

		if changed {
			c.index[i] = idx
			// 同步更新磁盘 JSON 和 IDX 文件
			if m := c.readMovieJSON(idx.Source, idx.VodId); m != nil {
				m.Type = idx.Type
				c.saveMovie(*m)
			}
			count++
		}
	}
	slog.Info("[ReclassifyAll]", "total", len(c.index), "updated", count)
}

func (c *Collector) GetMovie(source string, vodId int) *model.MovieInfo {
	path := c.moviePath(source, vodId)
	data, err := os.ReadFile(path)
	if err != nil { return nil }
	var m model.MovieInfo
	if err := json.Unmarshal(data, &m); err != nil { return nil }
	return &m
}

// readMovieJSON 只读 JSON 文件，不经过索引
func (c *Collector) readMovieJSON(source string, vodId int) *model.MovieInfo {
	path := c.moviePath(source, vodId)
	data, err := os.ReadFile(path)
	if err != nil { return nil }
	var m model.MovieInfo
	if err := json.Unmarshal(data, &m); err != nil { return nil }
	return &m
}

// GetMergedDetail 返回影片详情（磁盘数据 + 索引元信息合并）
func (c *Collector) GetMergedDetail(source string, id int) *model.MovieInfo {
	m := c.GetMovie(source, id)
	if m == nil { return nil }
	c.mu.RLock()
	if idx, ok := c.indexMap[source+":"+strconv.Itoa(id)]; ok && idx < len(c.index) {
		mi := c.index[idx]
		if mi.Type != "" { m.Type = mi.Type }
		if mi.Genre != "" { m.Genre = mi.Genre }
	}
	c.mu.RUnlock()
	m.Source = source
	return m
}

// FindMovieByTitleYear 按标题+年份跨源查找（标题经过归一化）
func (c *Collector) FindMovieByTitleYear(title string, year int, excludeSource string) ([]CmsSourceMatch, string) {
	normSource := normalizeIdxTitle(title)
	var matches []CmsSourceMatch
	sourceTitle := ""
	c.mu.RLock()
	defer c.mu.RUnlock()
	for _, idx := range c.index {
		if excludeSource != "" && idx.Source == excludeSource {
			continue
		}
		if idx.Year != year && year > 0 {
			continue
		}
		if normalizeIdxTitle(idx.Title) != normSource {
			continue
		}
		matches = append(matches, CmsSourceMatch{Source: idx.Source, VodId: idx.VodId})
		if sourceTitle == "" {
			sourceTitle = idx.Title
		}
	}
	return matches, sourceTitle
}

type CmsSourceMatch struct {
	Source string
	VodId  int
}

func (c *Collector) saveMovie(m model.MovieInfo) {
	// 采集时就检测质量：假多集（电视剧/短剧但全是HD/正片）修正为电影
	FixMovieType(&m)
	dir := filepath.Dir(c.moviePath(m.Source, m.VodId))
	os.MkdirAll(dir, 0755)
	data, _ := json.Marshal(m)
	os.WriteFile(c.moviePath(m.Source, m.VodId), data, 0644)
	idx := model.MovieIndex{
		VodId: m.VodId, Source: m.Source, Title: m.Title, CoverUrl: m.CoverUrl,
		Genre: m.Genre, Area: m.Area, Year: m.Year,
		Score: m.Score, Remark: m.Remark, Type: m.Type, RawType: m.RawType,
		Actors: m.Actors, Director: m.Director,
	}
	idxData, _ := json.Marshal(idx)
	os.WriteFile(c.movieIdxPath(m.Source, m.VodId), idxData, 0644)
}

func (c *Collector) AllMovies() []model.MovieIndex {
	c.mu.RLock()
	defer c.mu.RUnlock()
	result := make([]model.MovieIndex, len(c.index))
	copy(result, c.index)
	return result
}

func (c *Collector) Sources() []SourceCount {
	c.mu.RLock()
	defer c.mu.RUnlock()
	counts := make(map[string]int)
	for _, m := range c.index { if m.Source != "" { counts[m.Source]++ } }
	var result []SourceCount
	for name, cnt := range counts { result = append(result, SourceCount{Name: name, Count: cnt}) }
	sort.Slice(result, func(i, j int) bool { return result[i].Count > result[j].Count })
	return result
}

func (c *Collector) MoviesByPage(page, size int) model.PageResult {
	c.mu.RLock()
	idx := c.index; total := len(idx)
	c.mu.RUnlock()
	start := (page - 1) * size
	if start >= total { return model.PageResult{Items: []model.MovieInfo{}, Page: page, Size: size, Total: total} }
	end := start + size
	if end > total { end = total }
	items := make([]model.MovieInfo, 0, end-start)
	for _, mi := range idx[start:end] {
		c.ValidateMovieQuality(&mi)
		items = append(items, mi.ToMovieInfo())
	}
	return model.PageResult{Items: items, Page: page, Size: size, Total: total}
}

func (c *Collector) MoviesByGenre(genre string, page, size int, sortBy string, year int, area, typeFilter string) model.PageResult {
	c.mu.RLock()
	idx := c.index
	c.mu.RUnlock()

	// 过滤隐藏分类
	hiddenTypes := category.HiddenTypes()
	hiddenGenres := category.HiddenGenres()

	var matched []model.MovieIndex
	for _, mi := range idx {
		// 跳过隐藏的一级分类
		if hiddenTypes[mi.Type] {
			continue
		}
		// 跳过隐藏的二级分类（genre 匹配隐藏子分类）
		if len(hiddenGenres) > 0 {
			skip := false
			for _, g := range strings.Split(mi.Genre, ",") {
				g = strings.TrimSpace(g)
				if hiddenGenres[g] {
					skip = true
					break
				}
			}
			if skip { continue }
		}

		if genre == "" {
			matched = append(matched, mi)
		} else {
			if mi.Type == genre || mi.Genre == genre {
				matched = append(matched, mi)
				continue
			}
			// 按逗号/斜杠/句号拆分段匹配
			for _, sep := range []string{",", "/", "。", " "} {
				for _, g := range strings.Split(mi.Genre, sep) {
					g = strings.TrimSpace(g)
					g = strings.TrimRight(g, ". ")
					if g == "" { continue }
					if g == genre {
						matched = append(matched, mi)
						goto next
					}
					if strings.HasSuffix(g, "片") && strings.TrimSuffix(g, "片") == genre {
						matched = append(matched, mi)
						goto next
					}
				}
			}
			next:
		}
	}
	if area != "" {
		var f []model.MovieIndex
		for _, mi := range matched { if strings.Contains(mi.Area, area) { f = append(f, mi) } }
		matched = f
	}
	if year > 0 {
		var f []model.MovieIndex
		for _, mi := range matched { if mi.Year == year { f = append(f, mi) } }
		matched = f
	}
	if typeFilter != "" {
		var f []model.MovieIndex
		for _, mi := range matched { if mi.Type == typeFilter { f = append(f, mi) } }
		matched = f
	}

	dupMap := make(map[string]*model.MovieIndex)
	dupOrder := make([]string, 0, len(matched))
	for _, mi := range matched {
		key := normalizeIdxTitle(mi.Title)
		// 类型不同时不合并（避免"斗破苍穹 动漫"和"斗破苍穹 综艺"被判同片）
		if mi.Type != "" {
			key = key + "::" + mi.Type
		}
		if _, ok := dupMap[key]; !ok {
			dupMap[key] = &model.MovieIndex{VodId: mi.VodId, Source: mi.Source, Title: mi.Title, CoverUrl: mi.CoverUrl, Genre: mi.Genre, Area: mi.Area, Year: mi.Year, Score: mi.Score, Remark: mi.Remark, Type: mi.Type, Actors: mi.Actors, Director: mi.Director}
			dupOrder = append(dupOrder, key)
		} else {
			ex := dupMap[key]
			if mi.Score != "" && (ex.Score == "" || ex.Score == "0" || ex.Score == "0.0") { ex.Score = mi.Score; ex.Genre = mi.Genre; ex.Area = mi.Area; ex.Remark = mi.Remark; if ex.CoverUrl == "" { ex.CoverUrl = mi.CoverUrl } }
		}
	}
	deduped := make([]model.MovieIndex, 0, len(dupOrder))
	for _, key := range dupOrder {
		m := dupMap[key]
		// 只有有封面图的才进入索引列表展示，防止前端出现大量空图
		if m.CoverUrl != "" {
			deduped = append(deduped, *m)
		}
	}

	switch sortBy {
	case "score": sort.SliceStable(deduped, func(i, j int) bool { return parseScore(deduped[i].Score) > parseScore(deduped[j].Score) })
	case "time":  sort.SliceStable(deduped, func(i, j int) bool { return deduped[i].Year > deduped[j].Year })
	}
	total := len(deduped)
	start := (page - 1) * size
	if start >= total { return model.PageResult{Items: []model.MovieInfo{}, Page: page, Size: size, Total: total} }
	end := start + size
	if end > total { end = total }
	items := make([]model.MovieInfo, 0, end-start)
	for _, mi := range deduped[start:end] {
		c.ValidateMovieQuality(&mi)
		items = append(items, mi.ToMovieInfo())
	}
	return model.PageResult{Items: items, Page: page, Size: size, Total: total}
}

// FixMovieType 采集时质量检测：假多集（电视剧/短剧但全部是HD/正片）直接改类型为电影
// 在 saveMovie 和 BuildIdxFiles 时调用，源头修正，一劳永逸
func FixMovieType(m *model.MovieInfo) {
	if m == nil { return }
	if m.Type != "电视剧" && m.Type != "短剧" { return }
	if len(m.Plays) == 0 { return }

	totalEps := 0
	allSingle := true
	for _, pg := range m.Plays {
		totalEps += len(pg.Urls)
		for _, u := range pg.Urls {
			ep := strings.ToLower(strings.TrimSpace(u.Episode))
			if !isSingleEpisodeTag(ep) {
				allSingle = false
			}
		}
	}
	// 所有播放组每个都是单集标识 → 假多集，改电影
	if totalEps > 0 && allSingle && totalEps == countPlayGroupsWithUrls(m.Plays) {
		m.Type = "电影"
	}
}

// ValidateMovieQuality 查询时验证：从 JSON 修正 idx 数据（用于列表展示）
func (c *Collector) ValidateMovieQuality(mi *model.MovieIndex) {
	m := c.readMovieJSON(mi.Source, mi.VodId)
	if m == nil { return }

	// 对完整数据做质量检测（假多集→电影）
	FixMovieType(m)

	// 补充封面（旧 idx 可能缺失）
	if m.CoverUrl != "" && mi.CoverUrl == "" {
		mi.CoverUrl = m.CoverUrl
	}

	// JSON 修正后的 type 和 idx 不一致 → 以 JSON 为准
	if m.Type != "" && m.Type != mi.Type {
		mi.Type = m.Type
		mi.Remark = m.Remark
		return
	}

	// 其他 → 用 Classify 重分类（旧数据修复）
	if mi.Type == "其他" || m.Type == "其他" {
		bigType, _ := category.Classify(mi.RawType, mi.Genre, mi.Title)
		if bigType != "" && bigType != "其他" {
			mi.Type = bigType
			if m.Remark != "" {
				mi.Remark = m.Remark
			}
		}
	}
}

// isSingleEpisodeTag 判断剧集名是否为单集标识（非真正的集号）
func isSingleEpisodeTag(ep string) bool {
	if ep == "" { return false }
	// "第x集" → 真多集
	if strings.HasPrefix(ep, "第") && strings.HasSuffix(ep, "集") {
		return false
	}
	// 纯数字 → 真多集
	if _, err := strconv.Atoi(ep); err == nil {
		return false
	}
	singleTags := map[string]bool{
		"hd": true, "正片": true, "超清": true, "高清": true, "高清版": true,
		"蓝光": true, "4k": true, "1080p": true, "720p": true, "正片hd": true,
	}
	return singleTags[ep]
}

func countPlayGroupsWithUrls(plays []model.PlayGroup) int {
	n := 0
	for _, pg := range plays {
		if len(pg.Urls) > 0 { n++ }
	}
	return n
}
func (c *Collector) MoviesByGenreFull(genre string, page, size int, sortBy string, year int, area, typeFilter string) model.PageResult {
	pr := c.MoviesByGenre(genre, page, size, sortBy, year, area, typeFilter)
	for i, mi := range pr.Items {
		if m := c.GetMovie(mi.Source, mi.VodId); m != nil {
			pr.Items[i] = *m
		}
	}
	return pr
}

func normalizeIdxTitle(title string) string {
	title = strings.TrimSpace(title)
	title = strings.ToLower(title)
	for _, suf := range []string{"(国语)", "(粤语)", "(普通话)", "(英语)", "(原声)", "(中文字幕)", "(英文)"} { title = strings.TrimSuffix(title, suf) }
	var b strings.Builder
	for _, r := range title { if !unicode.IsSpace(r) { b.WriteRune(r) } }
	return b.String()
}

func parseScore(s string) float64 {
	if s == "" { return 0 }; var v float64
	if _, err := fmt.Sscanf(s, "%f", &v); err != nil { return 0 }
	return v
}

func (c *Collector) Genres() []string {
	c.mu.RLock(); defer c.mu.RUnlock()
	set := make(map[string]bool)
	for _, m := range c.index {
		if m.Genre != "" {
			for _, g := range strings.Split(m.Genre, ",") { g = strings.TrimSpace(g); if g != "" { set[g] = true } }
		}
	}
	var r []string; for g := range set { r = append(r, g) }; sort.Strings(r); return r
}

func (c *Collector) GenresByType(typeName string) []string {
	c.mu.RLock(); defer c.mu.RUnlock()
	set := make(map[string]bool)
	for _, m := range c.index {
		if m.Type == typeName && m.Genre != "" {
			for _, g := range strings.Split(m.Genre, ",") { g = strings.TrimSpace(g); if g != "" { set[g] = true } }
		}
	}
	var r []string; for g := range set { r = append(r, g) }; sort.Strings(r); return r
}

func (c *Collector) Types() []string {
	c.mu.RLock(); defer c.mu.RUnlock()
	set := make(map[string]bool)
	for _, m := range c.index { if m.Type != "" { set[m.Type] = true } }
	var r []string; for t := range set { r = append(r, t) }; sort.Strings(r); return r
}

func (c *Collector) TypeStats() map[string]int {
	c.mu.RLock(); defer c.mu.RUnlock()
	s := make(map[string]int)
	for _, m := range c.index { t := m.Type; if t == "" { t = "(空)" }; s[t]++ }
	return s
}

// RawTypeStats 按原始 type_name 统计数量（用于子分类统计）
func (c *Collector) RawTypeStats() map[string]int {
	c.mu.RLock(); defer c.mu.RUnlock()
	s := make(map[string]int)
	for _, m := range c.index {
		t := m.RawType
		if t == "" { t = m.Type }
		if t == "" { t = "(空)" }
		s[t]++
	}
	return s
}

// WalkIndex 遍历索引（readonly），fn 返回 false 停止
func (c *Collector) WalkIndex(fn func(m model.MovieIndex)) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	for _, idx := range c.index {
		fn(idx)
	}
}

func (c *Collector) Stats() model.CollectStats {
	c.mu.RLock(); defer c.mu.RUnlock()
	return model.CollectStats{
		TotalMovies: len(c.index), Failed: c.failed,
		LastCollect: c.lastCollect, Progress: c.progress, ProgressText: c.progressText,
		CollectCount: c.collectCnt, UpdateCount: c.updateCnt,
	}
}

// ───────────────────────────────────────────────────
// 续点采集 checkpoint
// ───────────────────────────────────────────────────

func (c *Collector) checkpointFilePath() string { return filepath.Join(c.dir, "collect_checkpoint.json") }

func (c *Collector) loadCheckpoint() {
	c.cpMu.Lock(); defer c.cpMu.Unlock()
	data, err := os.ReadFile(c.checkpointFilePath())
	if err != nil { return }
	var cp map[string]int
	if err := json.Unmarshal(data, &cp); err == nil && cp != nil { c.checkpoint = cp; slog.Info("[续点] 加载checkpoint", "条目", len(cp)) }
}

func (c *Collector) saveCheckpoint() {
	c.cpMu.Lock(); defer c.cpMu.Unlock()
	data, _ := json.Marshal(c.checkpoint)
	os.WriteFile(c.checkpointFilePath(), data, 0644)
}

func (c *Collector) setCheckpoint(source, typeID string, page int) {
	c.cpMu.Lock()
	key := source + ":" + typeID
	if page > c.checkpoint[key] { c.checkpoint[key] = page; c.cpMu.Unlock(); if page%10 == 0 { c.saveCheckpoint() } } else { c.cpMu.Unlock() }
}

func (c *Collector) getCheckpoint(source, typeID string) int {
	c.cpMu.Lock(); defer c.cpMu.Unlock()
	return c.checkpoint[source+":"+typeID]
}

func (c *Collector) ResetCheckpoint() {
	c.cpMu.Lock(); c.checkpoint = make(map[string]int); c.cpMu.Unlock()
	os.Remove(c.checkpointFilePath())
	slog.Info("[续点] checkpoint 已重置")
}

// verifyCheckoutPage 验证 checkpoint 记录的页码是否真的采集完成
// 访问该页最末几条数据，检查磁盘上是否有对应的 .json 文件
func (c *Collector) verifyCheckoutPage(src SourceInfo, typeID string, page int) bool {
	// 构造该页 URL 并请求
	baseUrl := src.Url
	if !strings.Contains(baseUrl, "?") { baseUrl += "?ac=list" } else { baseUrl += "&ac=list" }
	baseUrl += "&t=" + typeID + "&at=json"
	url := fmt.Sprintf("%s&pg=%d", baseUrl, page)

	data, err := c.fetchURL(url)
	if err != nil { return false }

	movies, err := ParseMovieList(data, src.Name)
	if err != nil || len(movies) == 0 { return false }

	// 只抽查最后3条，存在任一 .json 文件即认为该页已完成
	count := min(3, len(movies))
	sampleIDs := movies[len(movies)-count:]
	hits := 0
	for _, m := range sampleIDs {
		path := c.moviePath(src.Name, m.VodId)
		if _, err := os.Stat(path); err == nil { hits++ }
	}
	// 至少2/3命中才算验证通过
	return hits >= 2
}

// ───────────────────────────────────────────────────
// 采集核心
// ───────────────────────────────────────────────────

func (c *Collector) CollectAll(sources []SourceInfo) {
	if !c.collecting.CompareAndSwap(false, true) { fmt.Println("[COLLECT] 采集已在进行中"); return }
	defer c.collecting.Store(false)
	c.collectCnt = 0; c.updateCnt = 0; c.failed = 0

	totalSources := 0
	for _, src := range sources { if src.Active { totalSources++ } }
	fmt.Printf("[COLLECT] 开始采集 %d 个源\n", totalSources)

	detailCh := make(chan DetailBatch, 256)
	var detailWg sync.WaitGroup
	for i := 0; i < DetailWorkers; i++ {
		detailWg.Add(1)
		go func(id int) { defer detailWg.Done(); c.detailWorker(id, detailCh) }(i)
	}

	var listWg sync.WaitGroup
	for _, src := range sources {
		if !src.Active { continue }
		listWg.Add(1); src := src
		go func() { defer listWg.Done(); c.collectList(src, detailCh) }()
	}
	listWg.Wait()
	close(detailCh)
	detailWg.Wait()

	c.saveCheckpoint()
	now := time.Now()
	c.lastCollect = now.Format("2006-01-02 15:04:05")
	s := c.Stats()
	c.progressText = fmt.Sprintf("完成: %d 影片", s.TotalMovies)
	c.progress = 100
	fmt.Printf("[COLLECT] 采集完成: %d 部, 失败: %d\n", s.TotalMovies, s.Failed)
}

// collectList 先获取类型列表，然后逐类型采集
func (c *Collector) collectList(src SourceInfo, detailCh chan<- DetailBatch) {
	typeDefs := c.fetchTypeList(src.Url)
	if len(typeDefs) == 0 {
		fmt.Printf("[%s] 类型列表为空，降级到全量采集\n", src.Name)
		c.collectPagesForType(src, detailCh, "", 0, src.Pages)
		return
	}
	fmt.Printf("[%s] 类型列表: %d 个分类\n", src.Name, len(typeDefs))

	hasPid := false
	for _, td := range typeDefs {
		pid, _ := td.Pid.Int64()
		if pid > 0 { hasPid = true; break }
	}

	collectedNames := make(map[string]bool)
	for _, td := range typeDefs {
		pid, _ := td.Pid.Int64()
		if hasPid && pid != 0 { continue }
		if collectedNames[td.Name] { continue }
		collectedNames[td.Name] = true
		fmt.Printf("[%s] ➜ t=%d %s\n", src.Name, td.ID, td.Name)
		c.collectPagesForType(src, detailCh, fmt.Sprintf("%d", td.ID), td.ID, src.Pages)
	}
}

type sourceTypeDef struct {
	ID   int         `json:"type_id"`
	Pid  json.Number `json:"type_pid"`
	Name string      `json:"type_name"`
}

func (c *Collector) fetchTypeList(baseURL string) []sourceTypeDef {
	url := baseURL
	if !strings.Contains(url, "?") { url += "?ac=typelist" } else { url += "&ac=typelist" }
	data, err := c.fetchURL(url)
	if err != nil { slog.Warn("fetchTypeList", "url", url, "err", err); return nil }
	var wrapper struct { Class []sourceTypeDef `json:"class"` }
	decoder := json.NewDecoder(strings.NewReader(string(data)))
	decoder.UseNumber()
	if err := decoder.Decode(&wrapper); err != nil || len(wrapper.Class) == 0 {
		slog.Warn("fetchTypeList 解析失败", "err", err, "data_len", len(data))
		return nil
	}
	return wrapper.Class
}

func (c *Collector) collectPagesForType(src SourceInfo, detailCh chan<- DetailBatch, typeID string, typeIDInt int, maxPages int) {
	cpSkip := c.getCheckpoint(src.Name, typeID)
	startPage := 1
	if cpSkip > 0 {
		// 续点：先验证 checkpoint 页面数据是否完整
		fmt.Printf("[%s] t=%s 续点: 记录为第%d页, 验证中...\n", src.Name, typeID, cpSkip)
		verified := c.verifyCheckoutPage(src, typeID, cpSkip)
		if verified {
			startPage = cpSkip + 1
			fmt.Printf("[%s] t=%s 验证通过, 从第%d页继续\n", src.Name, typeID, startPage)
		} else {
			startPage = cpSkip
			fmt.Printf("[%s] t=%s 验证失败(数据不完整), 从第%d页重新采集\n", src.Name, typeID, startPage)
			// checkpoint 无效，回退
			c.setCheckpoint(src.Name, typeID, cpSkip-1)
		}
	}
	if startPage > maxPages { fmt.Printf("[%s] t=%s 全部已完成\n", src.Name, typeID); return }

	baseUrl := src.Url
	if !strings.Contains(baseUrl, "?") { baseUrl += "?ac=list" } else { baseUrl += "&ac=list" }
	baseUrl += "&t=" + typeID + "&at=json"

	if maxPages <= 0 { maxPages = 9999 }

	pendingIDs := make(map[int]bool)
	accum := newByteAccum(ListBytesLimit, pendingIDs, func(ids []int) { if len(ids) > 0 { detailCh <- DetailBatch{Source: src, IDs: ids} } })

	consecutiveEmpty := 0
	consecutiveErr := 0
	for pg := startPage; pg <= maxPages; pg++ {
		url := fmt.Sprintf("%s&pg=%d", baseUrl, pg)
		data, err := c.fetchURL(url)
		if err != nil {
			consecutiveErr++
			fmt.Printf("[%s] t=%s 第%d页失败 (连续%d次)\n", src.Name, typeID, pg, consecutiveErr)
			c.failed++
			if consecutiveErr >= 5 { fmt.Printf("[%s] t=%s 放弃\n", src.Name, typeID); break }
			time.Sleep(100 * time.Millisecond)
			continue
		}
		consecutiveErr = 0

		// 第一页检查 total 字段，为 0 则跳过整个类型
		if pg == startPage {
			total := extractTotal(data)
			if total == 0 {
				fmt.Printf("[%s] t=%s total=0, 跳过\n", src.Name, typeID)
				break
			}
		}

		movies, err := ParseMovieList(data, src.Name)
		if err != nil || len(movies) == 0 {
			consecutiveEmpty++
			if consecutiveEmpty >= EmptyLimit { fmt.Printf("[%s] t=%s 连续%d页为空\n", src.Name, typeID, EmptyLimit); break }
			time.Sleep(50 * time.Millisecond)
			continue
		}
		consecutiveEmpty = 0
		for _, m := range movies { c.AddMovie(m); accum.add(m.VodId, len(data)/len(movies)) }
		c.setCheckpoint(src.Name, typeID, pg)
		if pg%10 == 0 || pg == 1 { fmt.Printf("[%s] t=%s 第%d页\n", src.Name, typeID, pg) }
		time.Sleep(20 * time.Millisecond)
	}
	accum.flush()
	fmt.Printf("[%s] t=%s 完成\n", src.Name, typeID)
}

func (c *Collector) detailWorker(id int, batchCh <-chan DetailBatch) {
	for batch := range batchCh {
		if len(batch.IDs) == 0 { continue }
		ids := batch.IDs; src := batch.Source
		needsDetail := false
		for i := 0; i < 5 && i < len(ids); i++ {
			if m := c.GetMovie(src.Name, ids[i]); m == nil || len(m.Plays) == 0 { needsDetail = true; break }
		}
		if !needsDetail { continue }

		for i := 0; i < len(ids); i += DetailBatchSize {
			end := i + DetailBatchSize
			if end > len(ids) { end = len(ids) }
			batchIDs := ids[i:end]

			var wg sync.WaitGroup
			sem := make(chan struct{}, Concurrency)
			for _, vid := range batchIDs {
				wg.Add(1); vid := vid
				go func() {
					defer wg.Done()
					sem <- struct{}{}
					defer func() { <-sem }()
					if m := c.GetMovie(src.Name, vid); m != nil && len(m.Plays) > 0 { return }
					detail := c.fetchDetail(src.Url, src.Name, vid)
					if detail != nil {
						// 第3层分类: 基于完整detail数据(含播放URL)重推类型
						RefineType(detail)
						c.AddMovie(*detail)
					}
				}()
			}
			wg.Wait()
			if BatchDelay > 0 { time.Sleep(BatchDelay) }
		}
	}
}

func (c *Collector) fetchDetail(baseURL, source string, vodId int) *model.MovieInfo {
	url := baseURL
	if !strings.Contains(url, "?") { url += "?ac=detail" } else { url += "&ac=detail" }
	url += fmt.Sprintf("&ids=%d", vodId)
	data, err := c.fetchURL(url)
	if err != nil { return nil }
	movies, err := ParseMovieList(data, source)
	if err != nil || len(movies) == 0 { return nil }
	return &movies[0]
}

func (c *Collector) fetchURL(url string) ([]byte, error) {
	req, err := http.NewRequest("GET", url, nil)
	if err != nil { return nil, err }
	req.Header.Set("User-Agent", "Mozilla/5.0")
	resp, err := c.httpClient.Do(req)
	if err != nil { return nil, err }
	defer resp.Body.Close()
	return io.ReadAll(io.LimitReader(resp.Body, 2*1024*1024))
}

// extractTotal 从 JSON 响应中提取 total 字段，失败返回 -1
func extractTotal(data []byte) int {
	var wrapper struct {
		Total int `json:"total"`
	}
	if err := json.Unmarshal(data, &wrapper); err != nil {
		// 部分源可能使用 "count" 字段
		var cw struct {
			Count int `json:"count"`
		}
		if err2 := json.Unmarshal(data, &cw); err2 == nil {
			return cw.Count
		}
		return -1
	}
	return wrapper.Total
}

func (c *Collector) LoadAll() {
	c.mu.Lock(); defer c.mu.Unlock()
	c.index = nil; c.indexMap = make(map[string]int)
	slog.Info("[LoadAll] 开始扫描数据...")
	idxCount := countFilesWithSuffix(c.dir, ".idx")
	jsonCount := countFilesWithSuffix(c.dir, ".json")
	slog.Info("[LoadAll]", "idx", idxCount, "json", jsonCount)
	if idxCount < jsonCount/2 {
		slog.Info("[LoadAll] .idx 文件不足，开始批量生成...")
		generated := c.BuildIdxFiles()
		slog.Info("[LoadAll] 批量生成完成", "生成", generated)
	}
	type idxEntry struct { path string; src string; vid int }
	var legacyJson []idxEntry

	filepath.Walk(c.dir, func(path string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() { return nil }
		if !strings.HasSuffix(path, ".idx") && !strings.HasSuffix(path, ".json") { return nil }
		rel, _ := filepath.Rel(c.dir, path)
		parts := strings.Split(rel, string(filepath.Separator))
		if len(parts) < 3 { return nil }
		src := parts[0]; name := parts[len(parts)-1]
		vidStr := strings.TrimSuffix(name, filepath.Ext(name))
		vid, err := strconv.Atoi(vidStr)
		if err != nil { return nil }
		if strings.HasSuffix(path, ".idx") {
			if data, err := os.ReadFile(path); err == nil {
				var mi model.MovieIndex
				if err := json.Unmarshal(data, &mi); err == nil {
					c.indexMap[src+":"+strconv.Itoa(mi.VodId)] = len(c.index)
					c.index = append(c.index, mi)
				}
			}
		} else {
			legacyJson = append(legacyJson, idxEntry{path: path, src: src, vid: vid})
		}
		return nil
	})
	for _, e := range legacyJson {
		if _, ok := c.indexMap[e.src+":"+strconv.Itoa(e.vid)]; !ok {
			if data, err := os.ReadFile(e.path); err == nil {
				var m model.MovieInfo
				if err := json.Unmarshal(data, &m); err == nil {
					c.indexMap[e.src+":"+strconv.Itoa(m.VodId)] = len(c.index)
					c.index = append(c.index, model.MovieIndex{
						VodId: m.VodId, Source: m.Source, Title: m.Title,
						CoverUrl: m.CoverUrl, Genre: m.Genre, Area: m.Area,
						Year: m.Year, Score: m.Score, Remark: m.Remark,
						Type: m.Type, RawType: m.RawType,
						Actors: m.Actors, Director: m.Director,
					})
				}
			}
		}
	}
	// 填充 idx 文件中缺失的 CoverUrl（旧格式 idx 不含 coverUrl 字段）
	// 只对有索引但 CoverUrl 为空的数据读取 JSON 补充，最多扫描一次
	// 注意：Actors/Director 不在启动时补充（旧数据量太大），改为搜索时按需加载
	if len(c.index) > 0 {
		type fillJob struct { src string; vid int; idx int }
		var needCover []fillJob
		for i, mi := range c.index {
			if mi.CoverUrl == "" {
				needCover = append(needCover, fillJob{src: mi.Source, vid: mi.VodId, idx: i})
			}
		}
		if len(needCover) > 0 {
			slog.Info("[LoadAll] 补充封面地址", "缺失", len(needCover))
			for _, j := range needCover {
				if m := c.readMovieJSON(j.src, j.vid); m != nil && m.CoverUrl != "" {
					c.index[j.idx].CoverUrl = m.CoverUrl
				}
			}
		}
	}
	slog.Info("[LoadAll] 完成", "影片数", len(c.index))
}

func (c *Collector) SaveAll() {
	c.mu.RLock()
	idx := c.index
	c.mu.RUnlock()
	slog.Info("[SaveAll] 开始保存", "影片", len(idx))
	for _, mi := range idx {
		if m := c.GetMovie(mi.Source, mi.VodId); m != nil { c.saveMovie(*m) }
	}
	slog.Info("[SaveAll] 完成")
}

func (c *Collector) BuildIdxFiles() int {
	type job struct { path string; vid int; src string }
	var jobs []job
	filepath.Walk(c.dir, func(path string, info os.FileInfo, err error) error {
		if err != nil || info.IsDir() || !strings.HasSuffix(path, ".json") { return nil }
		rel, _ := filepath.Rel(c.dir, path)
		parts := strings.Split(rel, string(filepath.Separator))
		if len(parts) < 3 { return nil }
		src := parts[0]; name := parts[len(parts)-1]
		vidStr := strings.TrimSuffix(name, ".json")
		if vid, err := strconv.Atoi(vidStr); err == nil { jobs = append(jobs, job{path: path, vid: vid, src: src}) }
		return nil
	})
	generated := 0
	var wg sync.WaitGroup
	sem := make(chan struct{}, Concurrency)
	for _, j := range jobs {
		wg.Add(1); j := j
		go func() {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			data, err := os.ReadFile(j.path)
			if err != nil { return }
			var m model.MovieInfo
			if err := json.Unmarshal(data, &m); err != nil { return }
			FixMovieType(&m)  // 采集时检测：假多集改类型
			idxPath := c.movieIdxPath(j.src, j.vid)
			os.MkdirAll(filepath.Dir(idxPath), 0755)
			mi := model.MovieIndex{
				VodId: m.VodId, Source: m.Source, Title: m.Title,
				CoverUrl: m.CoverUrl, Genre: m.Genre, Area: m.Area,
				Year: m.Year, Score: m.Score, Remark: m.Remark,
				Type: m.Type, RawType: m.RawType,
				Actors: m.Actors, Director: m.Director,
			}
			d, _ := json.Marshal(mi)
			os.WriteFile(idxPath, d, 0644)
			generated++
		}()
	}
	wg.Wait()
	return generated
}

func countFilesWithSuffix(dir, suffix string) int {
	count := 0
	filepath.Walk(dir, func(path string, info os.FileInfo, err error) error {
		if err == nil && !info.IsDir() && strings.HasSuffix(path, suffix) { count++ }
		return nil
	})
	return count
}
