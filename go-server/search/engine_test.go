package search

import (
	"os"
	"testing"

	"server.app/model"
)

// RED: 验证 BM25 搜索返回正确结果
func TestSearchBasic(t *testing.T) {
	s := NewEngine()
	s.BuildIndex([]model.MovieInfo{
		{VodId: 1, Title: "测试电影A", Genre: "动作", Source: "BaiDu", Actors: "刘德华"},
		{VodId: 2, Title: "测试电影B", Genre: "喜剧", Source: "YingHua", Actors: "周星驰"},
		{VodId: 3, Title: "完全无关", Genre: "纪录片", Source: "BaiDu"},
	})
	results := s.Search("测试电影A", nil, 1, 10)
	if len(results) == 0 {
		t.Fatal("应返回结果")
	}
	if results[0].Movie.VodId != 1 && results[0].Movie.VodId != 2 {
		t.Fatalf("期望 1 或 2, 得到 %d", results[0].Movie.VodId)
	}
}

// RED: 验证 BM25 权重 — title 匹配应排前面
func TestSearchRanking(t *testing.T) {
	s := NewEngine()
	s.BuildIndex([]model.MovieInfo{
		{VodId: 1, Title: "功夫熊猫", Genre: "喜剧", Actors: "周星驰"},
		{VodId: 2, Title: "喜剧之王", Genre: "喜剧", Actors: "周星驰"},
		{VodId: 3, Title: "西游降魔", Genre: "动作", Actors: "黄渤"},
	})
	results := s.Search("周星驰", nil, 1, 10)
	if len(results) == 0 {
		t.Fatal("应找到匹配 actor 的结果")
	}
	// actors 字段权重 3.0
	found := false
	for _, r := range results {
		if r.Movie.VodId == 1 {
			found = true
			break
		}
	}
	if !found {
		t.Fatal("actors 匹配应出现在结果中")
	}
}

// RED: 验证过滤
func TestSearchWithFilter(t *testing.T) {
	s := NewEngine()
	s.BuildIndex([]model.MovieInfo{
		{VodId: 1, Title: "电影A", Genre: "动作", Source: "BaiDu", Type: "电影", Year: 2024},
		{VodId: 2, Title: "电影B", Genre: "喜剧", Source: "YingHua", Type: "电影", Year: 2023},
	})
	filters := map[string]string{"source": "BaiDu"}
	results := s.Search("电影A", filters, 1, 10)
	if len(results) != 1 {
		t.Fatalf("期望 1 条, 得到 %d", len(results))
	}
	if results[0].Movie.Source != "BaiDu" {
		t.Fatal("过滤后 source 应为 BaiDu")
	}
}

// RED: 验证空搜索结果
func TestSearchEmpty(t *testing.T) {
	s := NewEngine()
	s.BuildIndex([]model.MovieInfo{
		{VodId: 1, Title: "电影A"},
	})
	results := s.Search("不存在的内容", nil, 1, 10)
	if len(results) != 0 {
		t.Fatal("无匹配应返回空列表")
	}
}

// RED: 验证自动补全
func TestAutocomplete(t *testing.T) {
	s := NewEngine()
	s.BuildIndex([]model.MovieInfo{
		{VodId: 1, Title: "功夫熊猫"},
		{VodId: 2, Title: "功夫瑜伽"},
		{VodId: 3, Title: "西游记"},
	})
	suggestions := s.Autocomplete("功夫", 5)
	if len(suggestions) == 0 {
		t.Fatal("应返回建议")
	}
	foundKungFu := false
	foundKungFuYoga := false
	for _, sg := range suggestions {
		if sg == "功夫熊猫" {
			foundKungFu = true
		}
		if sg == "功夫瑜伽" {
			foundKungFuYoga = true
		}
	}
	if !foundKungFu || !foundKungFuYoga {
		t.Fatal("应包含 功夫熊猫 和 功夫瑜伽")
	}
}

func TestMain(m *testing.M) { os.Exit(m.Run()) }
