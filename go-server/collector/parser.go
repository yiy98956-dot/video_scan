package collector

import (
	"encoding/json"
	"fmt"
	"regexp"
	"strconv"
	"strings"

	"server.app/model"
	"server.app/category"
)

var episodeRe = regexp.MustCompile(`第\d+[集话期]`)

// ParseMovieList 解析采集源返回的列表页/详情页数据，支持 JSON 和 XML
func ParseMovieList(data []byte, source string) ([]model.MovieInfo, error) {
	body := strings.TrimSpace(string(data))
	if len(body) == 0 {
		return nil, nil
	}

	// 尝试 JSON
	if body[0] == '{' || body[0] == '[' {
		return parseJSONMovies(data, source)
	}
	// 尝试 XML
	return parseXMLMovies(data, source)
}

func parseJSONMovies(data []byte, source string) ([]model.MovieInfo, error) {
	// 尝试 {"list": [...]} 格式
	var wrapper struct {
		List []map[string]any `json:"list"`
	}
	if err := json.Unmarshal(data, &wrapper); err == nil && len(wrapper.List) > 0 {
		return parseMovieObjects(wrapper.List, source), nil
	}

	// 尝试 {"data": [...]} 格式（部分 CMS 源）
	var dataWrapper struct {
		Data []map[string]any `json:"data"`
	}
	if err := json.Unmarshal(data, &dataWrapper); err == nil && len(dataWrapper.Data) > 0 {
		return parseMovieObjects(dataWrapper.Data, source), nil
	}

	// 尝试顶层是数组
	var topArr []map[string]any
	if err := json.Unmarshal(data, &topArr); err == nil && len(topArr) > 0 {
		return parseMovieObjects(topArr, source), nil
	}

	// 尝试顶层是对象，字段内嵌数据
	var top map[string]any
	if err := json.Unmarshal(data, &top); err == nil {
		// 先检查是否有 vod_id/id 字段 → 单对象模式（详情 API 返回格式）
		if hasMovieField(top) {
			return []model.MovieInfo{objToMovie(top, source)}, nil
		}
		for _, v := range top {
			if arr, ok := v.([]any); ok && len(arr) > 0 {
				var objs []map[string]any
				for _, item := range arr {
					if m, ok := item.(map[string]any); ok {
						objs = append(objs, m)
					}
				}
				if len(objs) > 0 {
					return parseMovieObjects(objs, source), nil
				}
			}
		}
	}
	return nil, fmt.Errorf("无法解析 JSON")
}

// containsAny 检查字符串是否包含任意关键词
func containsAny(s string, kws ...string) bool {
	for _, kw := range kws {
		if strings.Contains(s, kw) {
			return true
		}
	}
	return false
}

// hasMovieField 检查 map 是否包含影片标识字段
func hasMovieField(m map[string]any) bool {
	for _, k := range []string{"vod_id", "id", "vod_name", "name", "title"} {
		if _, ok := m[k]; ok {
			return true
		}
	}
	return false
}

// hasPlayField 检查 map 是否包含播放地址字段
func hasPlayField(m map[string]any) bool {
	for _, k := range []string{"vod_play_url", "play_url", "vod_play_from"} {
		if v, ok := m[k]; ok {
			if s, ok := v.(string); ok && s != "" {
				return true
			}
		}
	}
	return false
}

// parseXMLMovies 解析 XML 格式的采集数据
func parseXMLMovies(data []byte, source string) ([]model.MovieInfo, error) {
	// XML 解析暂未实现，返回空
	return nil, nil
}

func parseMovieObjects(objs []map[string]any, source string) []model.MovieInfo {
	var movies []model.MovieInfo
	for _, obj := range objs {
		m := objToMovie(obj, source)
		movies = append(movies, m)
	}
	return movies
}

func objToMovie(obj map[string]any, source string) model.MovieInfo {
	getStr := func(keys ...string) string {
		for _, k := range keys {
			if v, ok := obj[k]; ok {
				switch val := v.(type) {
				case string:
					return val
				case float64:
					return strconv.FormatFloat(val, 'f', -1, 64)
				case json.Number:
					return val.String()
				default:
					if s := fmt.Sprintf("%v", val); s != "" && s != "<nil>" {
						return s
					}
				}
			}
		}
		return ""
	}
	getInt := func(keys ...string) int {
		s := getStr(keys...)
		if s == "" {
			return 0
		}
		if n, err := strconv.Atoi(s); err == nil {
			return n
		}
		f, err := strconv.ParseFloat(s, 64)
		if err != nil {
			return 0
		}
		return int(f)
	}

	vodId := getInt("vod_id", "id", "vodId")
	title := getStr("vod_name", "name", "title")
	coverUrl := getStr("vod_pic", "pic", "cover", "vod_img")
	year := getInt("vod_year", "year")
	area := getStr("vod_area", "area")
	genre := getStr("vod_class", "genre", "class_name", "class", "vod_type")
	director := getStr("vod_director", "director")
	actors := getStr("vod_actor", "actor", "actors")
	description := getStr("vod_content", "vod_blurb", "description", "des", "vod_description")
	score := getStr("vod_score", "score", "rating", "douban_score")
	remark := getStr("vod_remarks", "vod_remark", "remark", "remarks", "note", "vod_remark")
	from := getStr("vod_play_from", "from", "play_from")
	urlStr := getStr("vod_play_url", "url", "play_url", "link", "vod_url")
	listDate := getStr("vod_time", "vod_addtime", "addtime", "last_time", "vod_last", "vod_pubdate", "vod_time_add")

	playUrls := ParsePlayUrls(from, urlStr)

	// 如果标准 key 没找到 play URL，尝试暴力搜索可能的 key
	if len(playUrls) == 0 {
		seen := make(map[string]bool)
		for _, k := range []string{"vod_play_url", "play_url", "vod_url", "vurl", "down_url"} {
			if v, ok := obj[k]; ok {
				s, ok := v.(string)
				if ok && strings.TrimSpace(s) != "" {
					playUrls = ParsePlayUrls(from, s)
					if len(playUrls) > 0 {
						seen[k] = true
						break
					}
				}
			}
		}
		if len(playUrls) == 0 {
			for k, v := range obj {
				kl := strings.ToLower(k)
				if strings.Contains(kl, "play_from") || strings.Contains(kl, "play_server") || strings.Contains(kl, "play_note") || strings.Contains(kl, "play_pwd") {
					continue
				}
				if seen[k] {
					continue
				}
				if strings.Contains(kl, "play_url") || (strings.Contains(kl, "play") && strings.Contains(kl, "url")) {
					if s, ok := v.(string); ok && strings.TrimSpace(s) != "" {
						playUrls = ParsePlayUrls(from, s)
						if len(playUrls) > 0 {
							break
						}
					}
				}
			}
		}
	}
	rawTypeName := getStr("type_name", "typename", "vod_type_name")
	// 使用统一的分类逻辑
	mType, _ := category.Classify(rawTypeName, genre, title)
	if mType == "其他" {
		// 兜底：如果新逻辑没分出来，尝试用旧的特征推导
		mType = DeriveType(playUrls, remark, rawTypeName)
	}

	if rawTypeName == "" {
		// 兜底: 从推得的 type 反填
		rawTypeName = mType
	}

	return model.MovieInfo{
		VodId:    vodId,
		Title:    title,
		CoverUrl: coverUrl,
		Year:     year,
		Area:     area,
		Genre:    genre,
		Director: director,
		Actors:   actors,
		Description: description,
		Score:    score,
		Remark:   remark,
		Source:   source,
		Type:     mType,
		RawType:  rawTypeName,
		ListDate: listDate,
		Plays:    playUrls,
	}
}

// ParsePlayUrls 解析播放地址
// 格式: "hnm3u8$$$bjm3u8" (from) + "第01集$url1#第02集$url2" (urlStr)
// $$$ = 分组, # = 分集, $ = episode$url
func ParsePlayUrls(from, urlStr string) []model.PlayGroup {
	if urlStr == "" {
		return nil
	}

	groups := strings.Split(from, "$$$")
	urls := strings.Split(urlStr, "$$$")

	// 如果分组数与 URL 组数不匹配，按单一播放源处理
	if len(groups) != len(urls) {
		groups = []string{""}
		urls = []string{urlStr}
	}

	var result []model.PlayGroup
	for i, g := range groups {
		g = strings.TrimSpace(g)
		ug := ""
		if i < len(urls) {
			ug = urls[i]
		}
		group := model.PlayGroup{From: g}
		if g == "" {
			group.Name = "默认"
		} else {
			group.Name = g
		}
		episodes := strings.Split(ug, "#")
		for _, ep := range episodes {
			ep = strings.TrimSpace(ep)
			if ep == "" {
				continue
			}
			// 格式: "剧集名$url" 或 "剧集名$$url" (双$)
			parts := strings.SplitN(ep, "$$", 2)
			if len(parts) < 2 {
				parts = strings.SplitN(ep, "$", 2)
			}
			if len(parts) == 2 {
				group.Urls = append(group.Urls, model.PlayUrl{
					Episode: strings.TrimSpace(parts[0]),
					Url:     strings.TrimSpace(parts[1]),
				})
			} else if len(parts) == 1 && strings.TrimSpace(parts[0]) != "" {
				// 只有 URL，没有剧集名
				group.Urls = append(group.Urls, model.PlayUrl{
					Url: strings.TrimSpace(parts[0]),
				})
			}
		}
		if len(group.Urls) > 0 {
			result = append(result, group)
		}
	}
	return result
}

// DeriveType 从播放地址和备注推导影片一级分类
// 第1层: CMS 源 type_name → 精确匹配 + 后缀推断
func DeriveType(playUrls interface{}, remark string, cmsTypeNames ...string) string {
	// 1. 优先使用 CMS 提供的 type_name
	if len(cmsTypeNames) > 0 {
		for _, t := range cmsTypeNames {
			t = strings.TrimSpace(t)
			if t == "" { continue }
			if t == "电影" || t == "电影片" || t == "电影解说" {
				return "电影"
			}
			if t == "电视剧" || t == "连续剧" {
				return "电视剧"
			}
			if t == "动漫" || t == "动画" || t == "动漫片" || t == "国产动漫" || t == "日韩动漫" || t == "欧美动漫" || t == "港台动漫" || t == "动漫电影" || t == "里番动漫" {
				return "动漫"
			}
			if t == "综艺" || t == "综艺片" {
				return "综艺"
			}
			if t == "纪录片" || t == "纪录" {
				return "纪录片"
			}
			if t == "体育" || t == "体育赛事" || t == "体育片" || t == "足球" || t == "篮球" || t == "NBA" || t == "英超" || t == "西甲" || t == "搏击" {
				return "体育"
			}
			if t == "短剧" || t == "微短剧" || t == "动态漫" || t == "短片" {
				return "短剧"
			}
			if t == "少儿" || t == "儿童" {
				return "少儿"
			}
		}
		for _, t := range cmsTypeNames {
			if strings.HasSuffix(t, "片") {
				return "电影"
			}
			if strings.HasSuffix(t, "剧") {
				return "电视剧"
			}
			if strings.Contains(t, "综艺") {
				return "综艺"
			}
			if strings.Contains(t, "动漫") || strings.Contains(t, "动画") {
				return "动漫"
			}
		}
	}

	// 2. 检查剧集数
	switch v := playUrls.(type) {
	case []model.PlayGroup:
		total := 0
		for _, g := range v {
			total += len(g.Urls)
		}
		if total > 3 {
			return "电视剧"
		}
	case string:
		if episodeRe.MatchString(v) || strings.Count(v, "#") > 3 {
			return "电视剧"
		}
	}
	// 3. 检查备注
	if strings.Contains(remark, "集") || strings.Contains(remark, "连载") || strings.Contains(remark, "更新") {
		return "电视剧"
	}
	return "电影"
}

// RefineType 第3层分类: 基于完整detail数据进行精确推断
// 在detailWorker取到vod_play_url完整数据后调用
func RefineType(m *model.MovieInfo) {
	if m == nil { return }
	totalEpisodes := 0
	for _, g := range m.Plays {
		totalEpisodes += len(g.Urls)
	}

	// 规则1: 超过3集 → 一定是电视剧
	if totalEpisodes > 3 {
		if m.Type == "电影" {
			m.Type = "电视剧"
		}
		return
	}

	// 规则2: remark有强烈电视剧特征
	if containsAny(m.Remark, "连载", "更新至", "全集") {
		if m.Type == "电影" {
			m.Type = "电视剧"
			return
		}
	}
	if containsAny(m.Remark, "集") && !containsAny(m.Remark, "HD", "高清", "中字", "国语", "粤语", "英语") {
		if m.Type == "电影" {
			m.Type = "电视剧"
			return
		}
	}

	// 规则3: play URL episode名含"第X集" → 电视剧
	if totalEpisodes > 0 {
		for _, g := range m.Plays {
			for _, u := range g.Urls {
				if strings.Contains(u.Episode, "集") || strings.Contains(u.Episode, "话") || strings.Contains(u.Episode, "期") {
					if m.Type == "电影" {
						m.Type = "电视剧"
					}
					return
				}
			}
		}
	}
}
