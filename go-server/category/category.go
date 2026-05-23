// Package category 视频分类系统
package category

import (
	"sort"
	"strings"
	"sync"
)

var mu sync.RWMutex
var allCats []*Category
var catMap = make(map[int]*Category)
var aliasMap = make(map[string]*Category)

type Category struct {
	ID     int         `json:"id"`
	Pid    int         `json:"pid"`
	Name   string      `json:"name"`
	Alias  string      `json:"alias"`
	IsShow bool        `json:"is_show"`
	Sort   int         `json:"sort"`
	Subs   []*Category `json:"subs,omitempty"`
}

const (
	CatMovie       = 1
	CatTV          = 2
	CatShort       = 3
	CatAnime       = 4
	CatVariety     = 5
	CatDocumentary = 6
	CatKids        = 7
	CatSports      = 8
	CatNews        = 9
	CatOther       = 10
)

const DefaultCategoryID = CatMovie

func SetDataFile(path string) {}

func Init() {
	mu.Lock()
	defer mu.Unlock()
	allCats = BuildDefaultCategories()
	catMap = make(map[int]*Category)
	aliasMap = make(map[string]*Category)
	for _, cat := range allCats {
		catMap[cat.ID] = cat
		if cat.Alias != "" {
			aliasMap[cat.Alias] = cat
		}
		for _, sub := range cat.Subs {
			catMap[sub.ID] = sub
			if sub.Alias != "" {
				aliasMap[sub.Alias] = sub
			}
		}
	}
}

func BuildDefaultCategories() []*Category {
	roots := []*Category{
		{ID: 1, Pid: 0, Name: "电影", Alias: "movie", IsShow: true, Sort: 1},
		{ID: 2, Pid: 0, Name: "电视剧", Alias: "tv", IsShow: true, Sort: 2},
		{ID: 3, Pid: 0, Name: "短剧", Alias: "short", IsShow: true, Sort: 3},
		{ID: 4, Pid: 0, Name: "动漫", Alias: "anime", IsShow: true, Sort: 4},
		{ID: 5, Pid: 0, Name: "综艺", Alias: "variety", IsShow: true, Sort: 5},
		{ID: 6, Pid: 0, Name: "纪录片", Alias: "documentary", IsShow: true, Sort: 6},
		{ID: 7, Pid: 0, Name: "少儿", Alias: "kids", IsShow: true, Sort: 7},
		{ID: 8, Pid: 0, Name: "体育", Alias: "sports", IsShow: true, Sort: 8},
		{ID: 9, Pid: 0, Name: "资讯", Alias: "news", IsShow: true, Sort: 9},
		{ID: 10, Pid: 0, Name: "其他", Alias: "other", IsShow: true, Sort: 10},
	}
	subs := map[int][]*Category{
		1: subList(1, "动作","action", "喜剧","comedy", "爱情","romance", "科幻","sci-fi", "悬疑","suspense", "惊悚","thriller", "剧情","drama", "战争","war", "灾难","disaster", "犯罪","crime", "奇幻","fantasy", "文艺","literary", "动画电影","animated", "经典老片","classic", "伦理","ethics"),
		2: subList(2, "都市","urban", "古装","costume", "言情","romance_tv", "悬疑","suspense_tv", "谍战","spy", "军旅","military", "武侠","wuxia", "仙侠","xianxia", "年代","period", "家庭","family", "农村","rural", "网剧","web_series", "港剧","hongkong", "台剧","taiwan", "韩剧","korean", "美剧","american"),
		3: subList(3, "甜宠","sweet", "赘婿","soninlaw", "逆袭","comeback", "玄幻","fantasy_short", "古风","ancient", "复仇","revenge", "豪门","rich_family", "校园","campus", "穿越","time_travel", "都市逆袭","urban_reverse"),
		4: subList(4, "国漫","guoman", "日漫","riman", "欧美动漫","western", "热血","hot_blood", "恋爱","love", "奇幻","fantasy_anime", "冒险","adventure", "校园","campus_anime", "古风","ancient_anime", "治愈","healing", "悬疑","suspense_anime", "少儿动漫","kids_anime"),
		5: subList(5, "真人秀","reality", "选秀","talent", "音乐","music", "脱口秀","talk", "情感","emotion", "亲子","parenting", "户外","outdoor", "美食","food", "职场","workplace", "搞笑综艺","funny"),
		6: subList(6, "人文历史","history", "自然地理","nature", "军事","military_doc", "美食","food_doc", "人物","biography", "社会","society", "动物","animal", "科技","technology"),
		7: subList(7, "早教","early_edu", "儿歌","nursery", "动画片","cartoon", "绘本","picture_book", "益智","puzzle", "亲子节目","parenting_kids"),
		8: subList(8, "足球","football", "篮球","basketball", "格斗","fighting", "电竞","esports", "综合赛事","sports_other"),
		9: subList(9, "娱乐资讯","entertainment", "影视资讯","film_news", "社会热点","hot_news"),
	}
	for _, r := range roots {
		if s, ok := subs[r.ID]; ok {
			r.Subs = s
		}
	}
	return roots
}

func subList(pid int, args ...string) []*Category {
	var list []*Category
	baseID := pid * 100
	for i := 0; i+1 < len(args); i += 2 {
		id := baseID + i/2 + 1
		list = append(list, &Category{
			ID: id, Pid: pid, Name: args[i], Alias: args[i+1],
			IsShow: true, Sort: i/2 + 1,
		})
	}
	return list
}

func GetAll() []*Category {
	mu.RLock()
	defer mu.RUnlock()
	return allCats
}

func GetVisible() []*Category {
	mu.RLock()
	defer mu.RUnlock()
	var result []*Category
	for _, c := range allCats {
		if !c.IsShow {
			continue
		}
		cat := *c
		cat.Subs = nil
		for _, s := range c.Subs {
			if s.IsShow {
				cat.Subs = append(cat.Subs, &Category{ID: s.ID, Pid: s.Pid, Name: s.Name, Alias: s.Alias, IsShow: s.IsShow, Sort: s.Sort})
			}
		}
		result = append(result, &cat)
	}
	return result
}

func GetByID(id int) *Category {
	mu.RLock()
	defer mu.RUnlock()
	return catMap[id]
}

func GetByAlias(alias string) *Category {
	mu.RLock()
	defer mu.RUnlock()
	return aliasMap[alias]
}

func GetSubs(pid int) []*Category {
	mu.RLock()
	defer mu.RUnlock()
	if c, ok := catMap[pid]; ok {
		return c.Subs
	}
	return nil
}

func ToggleShow(id int) bool {
	mu.Lock()
	defer mu.Unlock()
	if c, ok := catMap[id]; ok {
		c.IsShow = !c.IsShow
		return c.IsShow
	}
	return false
}

func Update(id int, name, alias string, sort int, isShow bool) {
	mu.Lock()
	defer mu.Unlock()
	if c, ok := catMap[id]; ok {
		c.Name = name
		c.Alias = alias
		c.Sort = sort
		c.IsShow = isShow
	}
}

func AddSub(pid int, name, alias string) *Category {
	mu.Lock()
	defer mu.Unlock()
	if parent, ok := catMap[pid]; ok {
		id := pid*100 + len(parent.Subs) + 1
		sub := &Category{
			ID: id, Pid: pid, Name: name, Alias: alias,
			IsShow: true, Sort: len(parent.Subs) + 1,
		}
		parent.Subs = append(parent.Subs, sub)
		catMap[id] = sub
		if alias != "" {
			aliasMap[alias] = sub
		}
		return sub
	}
	return nil
}

func DeleteSub(id int) {
	mu.Lock()
	defer mu.Unlock()
	sub, ok := catMap[id]
	if !ok || sub.Pid == 0 {
		return
	}
	parent, ok := catMap[sub.Pid]
	if !ok {
		return
	}
	for i, s := range parent.Subs {
		if s.ID == id {
			parent.Subs = append(parent.Subs[:i], parent.Subs[i+1:]...)
			break
		}
	}
	delete(catMap, id)
	if sub.Alias != "" {
		delete(aliasMap, sub.Alias)
	}
}

func Save() error {
	return nil
}

func GetTreeAll() []TreeJSON {
	mu.RLock()
	defer mu.RUnlock()
	var result []TreeJSON
	for _, r := range allCats {
		item := TreeJSON{ID: r.ID, Name: r.Name, Alias: r.Alias, IsShow: r.IsShow}
		for _, s := range r.Subs {
			item.Subs = append(item.Subs, TreeJSON{
				ID: s.ID, Name: s.Name, Alias: s.Alias, IsShow: s.IsShow,
			})
		}
		sort.Slice(item.Subs, func(i, j int) bool { return item.Subs[i].ID < item.Subs[j].ID })
		result = append(result, item)
	}
	return result
}

func HiddenTypes() map[string]bool {
	mu.RLock()
	defer mu.RUnlock()
	res := make(map[string]bool)
	for _, c := range allCats {
		if !c.IsShow {
			res[c.Name] = true
		}
	}
	return res
}

func HiddenGenres() map[string]bool {
	mu.RLock()
	defer mu.RUnlock()
	res := make(map[string]bool)
	for _, c := range allCats {
		for _, s := range c.Subs {
			if !s.IsShow {
				res[s.Name] = true
			}
		}
	}
	return res
}

// sourceTypeMap - CMS 源 type_name 到系统分类映射
var sourceTypeMap = map[string]struct {
	CatID   int
	SubName string
}{
	"电影": {CatMovie, ""}, "电影片": {CatMovie, ""}, "动作片": {CatMovie, "动作"},
	"喜剧片": {CatMovie, "喜剧"}, "爱情片": {CatMovie, "爱情"}, "科幻片": {CatMovie, "科幻"},
	"恐怖片": {CatMovie, "惊悚"}, "剧情片": {CatMovie, "剧情"}, "伦理片": {CatMovie, "伦理"},
	"邵氏电影": {CatMovie, "经典老片"}, "动画片": {CatAnime, ""}, "动画电影": {CatMovie, "动画电影"},
	"电视剧": {CatTV, ""}, "连续剧": {CatTV, ""}, "大陆剧": {CatTV, "都市"},
	"内地剧": {CatTV, "都市"}, "国产剧": {CatTV, "都市"}, "香港剧": {CatTV, "港剧"},
	"港澳剧": {CatTV, "港剧"}, "台湾剧": {CatTV, "台剧"}, "韩国剧": {CatTV, "韩剧"},
	"韩剧": {CatTV, "韩剧"}, "日本剧": {CatTV, ""}, "日剧": {CatTV, ""},
	"欧美剧": {CatTV, "美剧"}, "美国剧": {CatTV, "美剧"}, "美剧": {CatTV, "美剧"},
	"海外剧": {CatTV, ""}, "泰国剧": {CatTV, ""}, "泰剧": {CatTV, ""},
	"马泰剧": {CatTV, ""}, "短剧": {CatShort, ""}, "爽文短剧": {CatShort, "逆袭"},
	"网剧": {CatTV, "网剧"}, "Netflix自制剧": {CatTV, ""},
	"动漫": {CatAnime, ""}, "动漫片": {CatAnime, ""}, "国产动漫": {CatAnime, "国漫"},
	"中国动漫": {CatAnime, "国漫"}, "日韩动漫": {CatAnime, "日漫"}, "日本动漫": {CatAnime, "日漫"},
	"欧美动漫": {CatAnime, "欧美动漫"}, "港台动漫": {CatAnime, "国漫"}, "海外动漫": {CatAnime, "欧美动漫"},
	"有声动漫": {CatAnime, ""}, "少儿动漫": {CatKids, "动画片"}, "里番动漫": {CatAnime, ""},
	"综艺": {CatVariety, ""}, "综艺片": {CatVariety, ""}, "大陆综艺": {CatVariety, "真人秀"},
	"港台综艺": {CatVariety, "搞笑综艺"}, "日韩综艺": {CatVariety, ""}, "欧美综艺": {CatVariety, ""},
	"演唱会": {CatVariety, "音乐"}, "纪录片": {CatDocumentary, ""}, "记录片": {CatDocumentary, ""},
	"纪实": {CatDocumentary, ""}, "体育赛事": {CatSports, "综合赛事"}, "足球": {CatSports, "足球"},
	"篮球": {CatSports, "篮球"}, "体育": {CatSports, "综合赛事"}, "台球": {CatSports, "综合赛事"},
	"斯诺克": {CatSports, "综合赛事"}, "格斗": {CatSports, "格斗"}, "其他赛事": {CatSports, "综合赛事"},
	"NBA": {CatSports, "篮球"}, "CBA": {CatSports, "篮球"}, "英超": {CatSports, "足球"},
	"西甲": {CatSports, "足球"}, "少儿": {CatKids, ""}, "儿童": {CatKids, ""},
	"儿歌": {CatKids, "儿歌"}, "早教": {CatKids, "早教"}, "儿童儿歌": {CatKids, "儿歌"},
	"科普学习": {CatKids, "益智"}, "资讯": {CatNews, ""}, "公告": {CatNews, "娱乐资讯"},
	"头条": {CatNews, "社会热点"}, "影视解说": {CatNews, "影视资讯"},
	"漫剧": {CatShort, ""}, "擦边短剧": {CatShort, ""}, "女频恋爱": {CatShort, "甜宠"},
	"反转爽剧": {CatShort, "逆袭"}, "脑洞悬疑": {CatShort, "悬疑"}, "年代穿越": {CatShort, "穿越"},
	"古装仙侠": {CatShort, "古风"}, "现代都市": {CatShort, "都市逆袭"}, "甜宠": {CatShort, "甜宠"},
	"赘婿": {CatShort, "赘婿"}, "短剧大全": {CatShort, ""},
}

// SmallTypeMap - 小类到大类的映射
var SmallTypeMap = map[string]string{
	"动作片": "电影", "喜剧片": "电影", "爱情片": "电影", "科幻片": "电影",
	"恐怖片": "电影", "悬疑片": "电影", "惊悚片": "电影", "剧情片": "电影",
	"战争片": "电影", "犯罪片": "电影", "灾难片": "电影", "伦理片": "电影",
	"动画片": "动漫", "动画电影": "动漫", "纪录片": "电影", "记录片": "电影",
	"历史片": "电影", "古装片": "电影", "家庭片": "电影", "奇幻片": "电影",
	"短片": "电影", "预告片": "电影", "4K电影": "电影", "Netflix电影": "电影",
	"邵氏电影": "电影", "港台三级": "电影", "韩国伦理": "电影",
	"西方伦理": "电影", "日本伦理": "电影", "两性课堂": "电影", "写真热舞": "电影",
	"国产剧": "电视剧", "大陆剧": "电视剧", "内地剧": "电视剧",
	"港剧": "电视剧", "香港剧": "电视剧", "台剧": "电视剧", "台湾剧": "电视剧",
	"韩剧": "电视剧", "韩国剧": "电视剧", "日剧": "电视剧", "日本剧": "电视剧",
	"美剧": "电视剧", "欧美剧": "电视剧", "泰剧": "电视剧", "泰国剧": "电视剧",
	"马泰剧": "电视剧", "港澳剧": "电视剧", "海外剧": "电视剧",
	"现代都市": "电视剧", "年代穿越": "电视剧", "脑洞悬疑": "电视剧",
	"女频恋爱": "电视剧", "反转爽剧": "电视剧", "爽文短剧": "电视剧",
	"擦边短剧": "电视剧", "漫剧": "电视剧", "Netflix自制剧": "电视剧",
	"军旅剧": "电视剧", "家庭剧": "电视剧", "都市剧": "电视剧",
	"悬疑剧": "电视剧", "古装剧": "电视剧", "现代剧": "电视剧",
	"大陆综艺": "综艺", "港台综艺": "综艺", "日韩综艺": "综艺", "欧美综艺": "综艺",
	"演唱会": "综艺", "晚会": "综艺", "脱口秀": "综艺", "真人秀": "综艺",
	"选秀": "综艺", "访谈": "综艺",
	"国产动漫": "动漫", "中国动漫": "动漫", "港台动漫": "动漫",
	"日韩动漫": "动漫", "日本动漫": "动漫", "欧美动漫": "动漫", "海外动漫": "动漫",
	"动漫电影": "动漫", "剧场版": "动漫", "少儿动画": "动漫", "有声动漫": "动漫",
	"里番动漫": "动漫",
	"自然纪录片": "纪录片", "人文纪录片": "纪录片", "历史纪录片": "纪录片",
	"科技纪录片": "纪录片", "军事纪录片": "纪录片", "社会纪录片": "纪录片",
	"短剧": "短剧", "微短剧": "短剧", "竖屏剧": "短剧", "网络短剧": "短剧",
	"反转爽文": "短剧", "古装仙侠": "短剧", "女恋总裁": "短剧",
	"现代言情": "短剧", "穿越年代": "短剧", "短剧大全": "短剧",
	"足球": "体育", "篮球": "体育", "网球": "体育", "斯诺克": "体育",
	"台球": "体育", "其他赛事": "体育",
	"电影解说": "其他", "影视解说": "其他", "预告解说": "其他",
	"科普学习": "其他", "儿童儿歌": "其他", "新闻资讯": "其他",
	"福利": "其他", "演员": "其他",
}

var BigTypeMap = map[string]string{
	"电影片": "电影", "连续剧": "电视剧", "综艺片": "综艺",
	"动漫片": "动漫", "记录片": "纪录片", "体育赛事": "体育",
}

var StandardBigTypes = []string{"电影", "电视剧", "综艺", "动漫", "纪录片", "短剧", "体育", "其他"}

func isStandardBigType(t string) bool {
	for _, s := range StandardBigTypes {
		if s == t {
			return true
		}
	}
	return false
}

func lookupSmallTypeOwner(smallType string) (string, bool) {
	if big, ok := SmallTypeMap[smallType]; ok {
		return big, ok
	}
	// 尝试补齐“片”、“剧”后缀再查一次
	if big, ok := SmallTypeMap[smallType+"片"]; ok {
		return big, ok
	}
	if big, ok := SmallTypeMap[smallType+"剧"]; ok {
		return big, ok
	}
	return "", false
}

func isAnimeTitle(title string) bool {
	low := strings.ToLower(title)
	// 明确动漫关键词
	for _, kw := range []string{"动漫", "动画", "anime", "番剧", "剧场版", "ova", "ona"} {
		if strings.Contains(low, kw) {
			return true
		}
	}
	// 著名动漫标题前缀/特征（可选增加更多）
	for _, kw := range []string{"名侦探柯南", "鬼灭之刃", "斗罗大陆", "斗破苍穹", "海贼王", "火影忍者", "蜡笔小新", "多啦a梦"} {
		if strings.Contains(low, kw) {
			return true
		}
	}
	return false
}

// Classify - 6步精准分类
func Classify(sourceBigType, sourceSmallType, title string) (string, string) {
	title = strings.TrimSpace(title)
	sbt := strings.TrimSpace(sourceBigType)
	sst := strings.TrimSpace(sourceSmallType)

	// 步骤0: 动漫标题识别 (极高优先级，防止动漫被误判为电影/电视剧)
	if isAnimeTitle(title) {
		return "动漫", sst
	}

	// 步骤1: 预处理源分类映射
	stdBigType := sbt
	if mapped, ok := BigTypeMap[sbt]; ok {
		stdBigType = mapped
	} else if owner, ok := lookupSmallTypeOwner(sbt); ok {
		// 如果 sbt 本身就在 SmallTypeMap 里（如“国产动漫”），直接作为 stdBigType
		stdBigType = owner
	}

	// 步骤2: 动漫/综艺/纪录片/短剧/体育等非影视大类，优先信任源分类
	// 如果源分类明确属于这些大类，直接返回，避免被后续的“第x集”误判为电视剧
	if sst != "" {
		if owner, ok := lookupSmallTypeOwner(sst); ok {
			if owner == "动漫" || owner == "综艺" || owner == "纪录片" || owner == "短剧" || owner == "体育" {
				return owner, sst
			}
		}
	}
	if stdBigType == "动漫" || stdBigType == "综艺" || stdBigType == "纪录片" || stdBigType == "短剧" || stdBigType == "体育" {
		// 尽量保留一个有意义的小类名
		resST := sst
		if resST == "" && sbt != stdBigType {
			resST = sbt
		}
		return stdBigType, resST
	}

	// 步骤3: 明确的电影特征 (防止“封神第一部电影”被误判为电视剧)
	if (strings.Contains(title, "电影") || strings.Contains(title, "剧场版")) && !strings.Contains(title, "解说") {
		return "电影", "无细分"
	}

	// 步骤4: 明确的电视剧特征
	if strings.Contains(title, "电视剧") || strings.Contains(title, "连续剧") || strings.Contains(title, "网剧") {
		return "电视剧", "无细分"
	}

	// 步骤5: 短剧关键词 (针对标题)
	for _, kw := range []string{"短剧", "微短剧", "竖屏剧"} {
		if strings.Contains(title, kw) {
			return "短剧", "无细分"
		}
	}

	// 步骤6: 电视剧关键词
	// 只有在不是明确的动漫/综艺等情况下，才根据标题判定为电视剧
	for _, kw := range []string{"季", "连载", "更新", "全集", "TV版"} {
		if strings.Contains(title, kw) {
			return "电视剧", "无细分"
		}
	}
	// 针对“第x集/季”的特殊判断
	if idx := strings.Index(title, "第"); idx != -1 && idx < len(title)-1 {
		afterDi := title[idx+len("第"):]
		// 如果“第”后面紧跟的是数字或中文数字，且包含“集”、“季”、“话”
		// 注意：此处移除了“部”，因为“第一部”在电影中非常常见
		hasTvWord := strings.Contains(afterDi, "集") || strings.Contains(afterDi, "季") || 
					 strings.Contains(afterDi, "话")
		if hasTvWord {
			// 特例排除：如果源分类本来就是电影类，且标题不含“电视剧”字样，不要仅因为“第x集”就强制转电视剧
			// 这种通常是类似“射雕英雄传第三集”的电影分段
			if stdBigType != "电影" {
				return "电视剧", "无细分"
			}
		}
	}

	// 步骤7: 电影关键词
	for _, kw := range []string{"院线", "HD", "BD", "蓝光", "720p", "1080p", "4K", "续集"} {
		if strings.Contains(title, kw) {
			return "电影", "无细分"
		}
	}

	// 步骤8: 兜底源分类映射 (处理电影/电视剧)
	if isStandardBigType(stdBigType) {
		resultBT := stdBigType
		resultST := "无细分"
		if sst != "" {
			if owner, ok := lookupSmallTypeOwner(sst); ok && owner == stdBigType {
				resultST = sst
			}
		}
		return resultBT, resultST
	}

	if sbt != "" {
		if owner, ok := lookupSmallTypeOwner(sbt); ok {
			return owner, sbt
		}
	}
	if sst != "" {
		if owner, ok := lookupSmallTypeOwner(sst); ok {
			return owner, sst
		}
	}

	return "其他", stdBigType
}

func MatchCategory(title, genre, catType string) (int, int) {
	if catType != "" {
		if m, ok := sourceTypeMap[catType]; ok {
			return m.CatID, 0
		}
	}
	if genre != "" {
		for _, g := range strings.Split(genre, ",") {
			g = strings.TrimSpace(g)
			if g == "" {
				continue
			}
		}
	}
	if title != "" {
		low := strings.ToLower(title)
		if strings.Contains(low, "短剧") || strings.Contains(low, "甜宠") {
			return CatShort, 0
		}
		if strings.Contains(low, "动漫") || strings.Contains(low, "动画") {
			return CatAnime, 0
		}
		if strings.Contains(low, "综艺") {
			return CatVariety, 0
		}
	}
	return DefaultCategoryID, 0
}

func FindSubByName(catID int, name string) *Category {
	mu.RLock()
	defer mu.RUnlock()
	if parent, ok := catMap[catID]; ok {
		for _, sub := range parent.Subs {
			if sub.Name == name {
				return sub
			}
		}
	}
	return nil
}

func LookupSourceType(name string) (struct{ CatID int; SubName string }, bool) {
	e, ok := sourceTypeMap[name]
	return struct{ CatID int; SubName string }{CatID: e.CatID, SubName: e.SubName}, ok
}

type TreeJSON struct {
	ID     int        `json:"id"`
	Name   string     `json:"name"`
	Alias  string     `json:"alias"`
	IsShow bool       `json:"is_show"`
	Subs   []TreeJSON `json:"subs,omitempty"`
}

func GetTree() []TreeJSON {
	roots := GetVisible()
	var result []TreeJSON
	for _, r := range roots {
		item := TreeJSON{ID: r.ID, Name: r.Name, Alias: r.Alias, IsShow: r.IsShow}
		for _, s := range r.Subs {
			item.Subs = append(item.Subs, TreeJSON{
				ID: s.ID, Name: s.Name, Alias: s.Alias, IsShow: s.IsShow,
			})
		}
		sort.Slice(item.Subs, func(i, j int) bool { return item.Subs[i].ID < item.Subs[j].ID })
		result = append(result, item)
	}
	return result
}
