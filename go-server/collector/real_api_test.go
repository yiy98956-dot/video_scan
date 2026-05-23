package collector

import (
	"testing"

	"server.app/model"
)

// RED: 验证 ParseMovieList 解析实际 CMS 格式 XML（vod_id/vod_name/vod_pic 等字段）
func TestParseMovieListRealXML(t *testing.T) {
	// 实际 CMS API 返回的格式（部分源使用 vod_ 前缀）
	xmlStr := `<?xml version="1.0" encoding="utf-8"?>
<rss>
<list>
<vod>
<vod_id>1001</vod_id>
<vod_name>测试电影</vod_name>
<vod_pic>https://example.com/pic.jpg</vod_pic>
<vod_year>2024</vod_year>
<vod_area>中国大陆</vod_area>
<vod_class>动作</vod_class>
<vod_director>张导</vod_director>
<vod_actor>刘演员</vod_actor>
<vod_content>简介</vod_content>
<vod_score>8.5</vod_score>
<vod_remarks>高清</vod_remarks>
<vod_play_from>bdzy</vod_play_from>
<vod_play_url>第01集$url1#第02集$url2</vod_play_url>
<vod_time>2026-05-10</vod_time>
</vod>
</list>
</rss>`
	movies, err := ParseMovieList([]byte(xmlStr), "BaiDu")
	if err != nil {
		t.Fatalf("解析 XML 失败: %v", err)
	}
	if len(movies) != 1 {
		t.Fatalf("期望 1 条, 得到 %d", len(movies))
	}
	m := movies[0]
	if m.VodId != 1001 || m.Title != "测试电影" {
		t.Fatalf("vodId/title 错误: %d/%s", m.VodId, m.Title)
	}
	if m.CoverUrl != "https://example.com/pic.jpg" {
		t.Fatalf("cover 错误: %s", m.CoverUrl)
	}
	if m.Year != 2024 || m.Area != "中国大陆" {
		t.Fatal("year/area 错误")
	}
	if m.Genre != "动作" {
		t.Fatal("genre 错误")
	}
	if m.Director != "张导" || m.Actors != "刘演员" {
		t.Fatal("director/actors 错误")
	}
	if m.Score != "8.5" {
		t.Fatal("score 错误")
	}
	// CRITICAL: Plays 必须被解析出来！
	if len(m.Plays) == 0 {
		t.Fatal("CRITICAL: plays 为空！播放地址未解析")
	}
	if m.Plays[0].Urls[0].Url != "url1" {
		t.Fatalf("play url 错误: %s", m.Plays[0].Urls[0].Url)
	}
}

// RED: 验证 ParseMovieList 解析实际 JSON 数组格式（CMS 常见响应）
func TestParseMovieListRealJSON(t *testing.T) {
	jsonStr := `{
		"code": 1,
		"msg": "success",
		"page": 1,
		"pagecount": 10,
		"limit": "40",
		"total": 385,
		"list": [
			{
				"vod_id": 2001,
				"vod_name": "测试电影B",
				"vod_pic": "https://cdn.com/b.jpg",
				"vod_year": "2025",
				"vod_area": "美国",
				"vod_class": "科幻,冒险",
				"vod_director": "斯导演",
				"vod_actor": "汤演员",
				"vod_content": "精彩电影",
				"vod_score": "9.0",
				"vod_remarks": "4K",
				"vod_play_from": "hnm3u8",
				"vod_play_url": "第01集$b_url1#第02集$b_url2",
				"type_name": "电影",
				"vod_time": "2026-05-09"
			}
		]
	}`
	movies, err := ParseMovieList([]byte(jsonStr), "YingHua")
	if err != nil {
		t.Fatalf("解析 JSON 失败: %v", err)
	}
	if len(movies) != 1 {
		t.Fatalf("期望 1 条, 得到 %d", len(movies))
	}
	m := movies[0]
	if m.VodId != 2001 || m.Title != "测试电影B" {
		t.Fatal("vodId/title 错误")
	}
	if len(m.Plays) == 0 {
		t.Fatal("CRITICAL: plays 为空！")
	}
	if m.Plays[0].Urls[0].Url != "b_url1" {
		t.Fatal("play url 错误")
	}
}

// RED: 验证 ParseMovieList 解析 JSON 顶层数组格式（无 list 包装）
func TestParseMovieListRawArrayReal(t *testing.T) {
	jsonStr := `[
		{"vod_id":3001,"vod_name":"数组电影A","vod_class":"喜剧","vod_score":"7.5","vod_play_from":"bdzy","vod_play_url":"ep1$u1#ep2$u2"},
		{"vod_id":3002,"vod_name":"数组电影B","vod_class":"剧情","vod_score":"8.0"}
	]`
	movies, err := ParseMovieList([]byte(jsonStr), "Test")
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if len(movies) != 2 {
		t.Fatalf("期望 2 条, 得到 %d", len(movies))
	}
	// 第一条有 plays
	if len(movies[0].Plays) == 0 {
		t.Fatal("CRITICAL: 第一条应有 plays")
	}
	// 第二条无 plays（不是 nil 问题，而是 len==0 问题）
	if len(movies[1].Plays) != 0 {
		t.Fatal("第二条应无 plays")
	}
	if movies[1].Score != "8.0" {
		t.Fatal("score 错误")
	}
}

// RED: 验证 len(m.Plays) == 0 能匹配空切片（非 nil）
func TestCollectorFetchDetailsCondition(t *testing.T) {
	// 模拟: 影片从 JSON 解析后 Plays 是 nil 或空切片
	m1 := model.MovieInfo{VodId: 1, Source: "BaiDu"}
	m2 := model.MovieInfo{VodId: 2, Source: "BaiDu", Plays: []model.PlayGroup{}}

	// 用 len() == 0 检查
	if len(m1.Plays) != 0 {
		t.Fatal("m1.Plays 应被视为空")
	}
	if len(m2.Plays) != 0 {
		t.Fatal("m2.Plays 应被视为空")
	}

	// 用 == nil 检查 — m2 的 Plays 是 [] 不是 nil！
	if m1.Plays == nil {
		// 这是 nil — 可以匹配
	} else {
		t.Fatal("m1.Plays 应为 nil")
	}
	if m2.Plays == nil {
		t.Fatal("BUG: m2.Plays 是空切片不是 nil！len()==0 但 != nil")
	}
	// 这就是 bug: m2.Plays != nil 但 len(m2.Plays) == 0
}

// RED: 验证 collector.SaveAll + LoadAll 保持 Plays
func TestCollectorSaveLoadPlays(t *testing.T) {
	dir := t.TempDir()
	c := NewCollector(dir)
	c.AddMovie(model.MovieInfo{
		VodId: 42, Title: "测试", Source: "BaiDu",
		Plays: []model.PlayGroup{{
			From: "bdzy", Name: "线路1",
			Urls: []model.PlayUrl{{Episode: "第01集", Url: "http://cdn/test.m3u8"}},
		}},
	})
	c.SaveAll()

	c2 := NewCollector(dir)
	c2.LoadAll()
	movies := c2.AllMovies()
	if len(movies) != 1 {
		t.Fatalf("期望 1 条, 得到 %d", len(movies))
	}
	// plays 不存储在 MovieIndex 中
	loaded := c2.GetMovie("BaiDu", 42)
	if loaded == nil || len(loaded.Plays) == 0 {
		t.Fatal("CRITICAL: LoadAll 后 plays 丢失！")
	}
}

// RED: 验证 CollectAll 后 Stats 正确
func TestCollectAllStats(t *testing.T) {
	dir := t.TempDir()
	c := NewCollector(dir)
	stats := c.Stats()
	if stats.Collecting {
		t.Fatal("不应正在采集")
	}
}
