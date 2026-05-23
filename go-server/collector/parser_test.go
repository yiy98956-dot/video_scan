package collector

import (
	"testing"
)

// RED: 验证 JSON 解析 — Apple CMS 标准格式
func TestParseMovieListJSON(t *testing.T) {
	jsonStr := `{
		"list": [
			{
				"vod_id": 1001,
				"vod_name": "测试电影A",
				"vod_pic": "https://example.com/a.jpg",
				"vod_year": "2024",
				"vod_area": "中国大陆",
				"vod_class": "动作,科幻",
				"vod_director": "张导演",
				"vod_actor": "刘演员",
				"vod_content": "电影简介",
				"vod_score": "8.5",
				"vod_remarks": "1080P",
				"vod_play_from": "bdzy$$$qqzy",
				"vod_play_url": "第01集$url1#第02集$url2$$$第1集$qurl1#第2集$qurl2",
				"type_name": "电影",
				"vod_time": "2026-05-10"
			}
		]
	}`
	movies, err := ParseMovieList([]byte(jsonStr), "BaiDu")
	if err != nil {
		t.Fatal(err)
	}
	if len(movies) != 1 {
		t.Fatalf("期望 1 条, 得到 %d", len(movies))
	}
	m := movies[0]
	if m.Title != "测试电影A" || m.VodId != 1001 {
		t.Fatalf("title/vodId 错误: %s/%d", m.Title, m.VodId)
	}
	if m.CoverUrl != "https://example.com/a.jpg" {
		t.Fatal("cover 错误")
	}
	if m.Year != 2024 || m.Area != "中国大陆" {
		t.Fatal("year/area 错误")
	}
	if m.Genre != "动作,科幻" {
		t.Fatal("genre 错误")
	}
	if m.Director != "张导演" || m.Actors != "刘演员" {
		t.Fatal("director/actors 错误")
	}
	if m.Score != "8.5" || m.Remark != "1080P" {
		t.Fatal("score/remark 错误")
	}
	if m.Type != "电影" {
		t.Fatal("type 错误")
	}
	if len(m.Plays) != 2 {
		t.Fatalf("期望 2 个播放组, 得到 %d", len(m.Plays))
	}
	if len(m.Plays[0].Urls) != 2 {
		t.Fatalf("期望 2 个剧集, 得到 %d", len(m.Plays[0].Urls))
	}
	if m.Plays[0].Urls[0].Episode != "第01集" {
		t.Fatalf("episode: 期望 第01集, 得到 %s", m.Plays[0].Urls[0].Episode)
	}
}

// RED: 验证 XML 解析
func TestParseMovieListXML(t *testing.T) {
	xmlStr := `<?xml version="1.0"?>
<rss><channel>
<item>
<id>2001</id>
<name>XML电影</name>
<pic>https://example.com/x.jpg</pic>
<year>2023</year>
<type>喜剧</type>
<director>王导演</director>
<actor>赵演员</actor>
<des>XML简介</des>
<score>9.0</score>
<note>更新第10集</note>
<from>bdzy</from>
<url>第01集$ep1.m3u8#第02集$ep2.m3u8</url>
<last>2026-05-09</last>
</item>
</channel></rss>`
	movies, err := ParseMovieList([]byte(xmlStr), "BaiDu")
	if err != nil {
		t.Fatal(err)
	}
	if len(movies) != 1 {
		t.Fatalf("期望 1 条, 得到 %d", len(movies))
	}
	m := movies[0]
	if m.VodId != 2001 || m.Title != "XML电影" {
		t.Fatal("vodId/title 错误")
	}
	if m.Genre != "喜剧" || m.Score != "9.0" {
		t.Fatal("genre/score 错误")
	}
	if m.Remark != "更新第10集" {
		t.Fatal("remark 错误")
	}
	if len(m.Plays) == 0 || m.Plays[0].Urls[0].Episode != "第01集" {
		t.Fatal("plays 解析错误")
	}
}

// RED: 验证播放 URL 解析 — $$ 分组 + # 分集
func TestParsePlayUrls(t *testing.T) {
	from := "hnm3u8$$$bjm3u8"
	urlStr := "第01集$url1#第02集$url2$$$第1集$qurl1#第2集$qurl2"
	groups := ParsePlayUrls(from, urlStr)
	if len(groups) != 2 {
		t.Fatalf("期望 2 组, 得到 %d", len(groups))
	}
	if groups[0].From != "hnm3u8" || groups[0].Name != "线路1" {
		t.Fatal("第一组 from/name 错误")
	}
	if len(groups[0].Urls) != 2 {
		t.Fatalf("第一组期望 2 集, 得到 %d", len(groups[0].Urls))
	}
	if groups[0].Urls[0].Episode != "第01集" {
		t.Fatal("episode 错误")
	}
}

// RED: 验证类型推导
func TestDeriveType(t *testing.T) {
	tests := []struct {
		input string
		want  string
	}{
		{"电影", "电影"},
		{"第01集$url#第02集$url2#第03集$url3", "电视剧"},
		{"更新第999集", "电视剧"},
		{"", "电影"}, // 默认
	}
	for _, tt := range tests {
		got := DeriveType(tt.input, "")
		if got != tt.want {
			t.Errorf("DeriveType(%q) = %q, 期望 %q", tt.input, got, tt.want)
		}
	}
}

// RED: 验证 JSON 无 list 兜底
func TestParseMovieListRawArray(t *testing.T) {
	jsonStr := `[
		{"vod_id": 3001, "vod_name": "数组格式"},
		{"vod_id": 3002, "vod_name": "第二条"}
	]`
	movies, err := ParseMovieList([]byte(jsonStr), "Test")
	if err != nil {
		t.Fatal(err)
	}
	if len(movies) != 2 {
		t.Fatalf("期望 2 条, 得到 %d", len(movies))
	}
}
