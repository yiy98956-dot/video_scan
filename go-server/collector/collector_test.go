package collector

import (
	"os"
	"testing"

	"server.app/model"
)

// RED: 验证 Collector 添加和获取影片
func TestCollectorAddAndList(t *testing.T) {
	dir := t.TempDir()
	c := NewCollector(dir)
	m := model.MovieInfo{VodId: 100, Title: "测试", Source: "BaiDu", Type: "电影"}
	c.AddMovie(m)
	movies := c.AllMovies()
	if len(movies) != 1 {
		t.Fatalf("期望 1 条, 得到 %d", len(movies))
	}
}

// RED: 验证去重 — 相同 vodId + source 覆盖
func TestCollectorDedup(t *testing.T) {
	dir := t.TempDir()
	c := NewCollector(dir)
	c.AddMovie(model.MovieInfo{VodId: 1, Title: "原版", Source: "BaiDu"})
	c.AddMovie(model.MovieInfo{VodId: 1, Title: "更新版", Source: "BaiDu"})
	movies := c.AllMovies()
	if len(movies) != 1 {
		t.Fatalf("去重后期望 1 条, 得到 %d", len(movies))
	}
	if movies[0].Title != "更新版" {
		t.Fatal("应保留最新版本")
	}
}

// RED: 验证磁盘持久化
func TestCollectorPersistence(t *testing.T) {
	dir := t.TempDir()
	c1 := NewCollector(dir)
	c1.AddMovie(model.MovieInfo{
		VodId: 42, Title: "持久化测试", Source: "BaiDu", Genre: "动作", Score: "8.0",
		Plays: []model.PlayGroup{{From: "bdzy", Urls: []model.PlayUrl{{Episode: "第01集", Url: "http://cdn/test.m3u8"}}}},
	})
	c1.SaveAll()

	c2 := NewCollector(dir)
	c2.LoadAll()
	movies := c2.AllMovies()
	if len(movies) != 1 {
		t.Fatalf("持久化后期望 1 条, 得到 %d", len(movies))
	}
	if movies[0].VodId != 42 || movies[0].Score != "8.0" {
		t.Fatal("字段不匹配")
	}
	// plays 不存储在 MovieIndex 中，需通过 GetMovie 验证
	loaded := c2.GetMovie("BaiDu", 42)
	if loaded == nil || len(loaded.Plays) == 0 {
		t.Fatal("plays 未持久化")
	}
}

// RED: 验证 Sources() 返回源信息
func TestCollectorSources(t *testing.T) {
	dir := t.TempDir()
	c := NewCollector(dir)
	c.AddMovie(model.MovieInfo{VodId: 1, Source: "BaiDu"})
	c.AddMovie(model.MovieInfo{VodId: 2, Source: "BaiDu"})
	c.AddMovie(model.MovieInfo{VodId: 3, Source: "YingHua"})
	srcs := c.Sources()
	if len(srcs) != 2 {
		t.Fatalf("期望 2 个源, 得到 %d", len(srcs))
	}
	found := false
	for _, s := range srcs {
		if s.Name == "BaiDu" && s.Count == 2 {
			found = true
		}
	}
	if !found {
		t.Fatal("BaiDu 计数应为 2")
	}
}

func TestMain(m *testing.M) {
	os.Exit(m.Run())
}

// ===== TRD: byteAccum 单元测试 =====

func TestByteAccum_NoTriggerBelowThreshold(t *testing.T) {
	var triggered [][]int
	pending := make(map[int]bool)
	acc := newByteAccum(100000, pending, func(ids []int) {
		triggered = append(triggered, ids)
	})
	// 只累积 40KB (< 100KB)
	acc.add(101, 40000)
	acc.add(102, 40000)
	if len(triggered) != 0 {
		t.Fatalf("80KB 不应触发, 实际 %d 次", len(triggered))
	}
}

func TestByteAccum_TriggersAtThreshold(t *testing.T) {
	var triggered [][]int
	pending := make(map[int]bool)
	acc := newByteAccum(100000, pending, func(ids []int) {
		batch := make([]int, len(ids))
		copy(batch, ids)
		triggered = append(triggered, batch)
	})
	// 3批累积 120KB → 触发
	acc.add(101, 40000)
	acc.add(102, 40000)
	acc.add(103, 40000)
	if len(triggered) != 1 {
		t.Fatalf("期望 1 次触发, 实际 %d 次", len(triggered))
	}
	if len(triggered[0]) != 3 {
		t.Fatalf("期望 3 个 ID, 实际 %d 个: %v", len(triggered[0]), triggered[0])
	}
}

func TestByteAccum_FlushEmitsRemaining(t *testing.T) {
	var triggered [][]int
	pending := make(map[int]bool)
	acc := newByteAccum(100000, pending, func(ids []int) {
		batch := make([]int, len(ids))
		copy(batch, ids)
		triggered = append(triggered, batch)
	})
	// 40KB, 不到阈值
	acc.add(201, 40000)
	// 手动 flush
	acc.flush()
	if len(triggered) != 1 {
		t.Fatalf("flush 后期望 1 次触发, 实际 %d", len(triggered))
	}
	if len(triggered[0]) != 1 || triggered[0][0] != 201 {
		t.Fatal("flush 应发射剩余 ID")
	}
}

func TestByteAccum_MultipleTriggers(t *testing.T) {
	var triggered int
	pending := make(map[int]bool)
	acc := newByteAccum(100000, pending, func(ids []int) {
		triggered++
	})
	// 每 3 个 ID = 120KB → 触发一次, 重复 2 轮 = 2 次触发
	for i := 0; i < 6; i++ {
		acc.add(1000+i, 40000)
	}
	if triggered != 2 {
		t.Fatalf("期望 2 次触发, 实际 %d", triggered)
	}
	// 第六个 ID 后 pending 应清空
	if len(pending) != 0 {
		t.Fatalf("触发后 pending 应清空, 实际 %d 个", len(pending))
	}
}

// ===== TRD: 磁盘保护测试 =====

func TestAddMovie_DoesNotOverwriteDetailWithList(t *testing.T) {
	dir := t.TempDir()
	col := NewCollector(dir)

	// 1. 先写一条完整数据到磁盘 (模拟已有详情)
	full := model.MovieInfo{
		VodId: 100, Source: "Test", Title: "完整影片",
		Score: "9.0", Year: 2026, Area: "中国",
		Plays: []model.PlayGroup{{From: "test", Urls: []model.PlayUrl{{Episode: "1", Url: "https://play"}}}},
	}
	col.saveMovie(full)

	// 2. 用列表数据 (无plays) 调用 AddMovie
	listData := model.MovieInfo{
		VodId: 100, Source: "Test", Title: "完整影片",
	}
	col.AddMovie(listData)

	// 3. 从磁盘读取, 验证 plays 还在
	loaded := col.GetMovie("Test", 100)
	if loaded == nil {
		t.Fatal("读取失败")
	}
	if len(loaded.Plays) == 0 {
		t.Fatal("列表数据覆盖了磁盘上的plays!")
	}
	if loaded.Score != "9.0" {
		t.Fatalf("期望 score=9.0, 实际=%s", loaded.Score)
	}
}

func TestAddMovie_NewMovieWritesToDisk(t *testing.T) {
	dir := t.TempDir()
	col := NewCollector(dir)

	// 新影片 (文件不存在)
	m := model.MovieInfo{VodId: 200, Source: "Test", Title: "新片", Genre: "动作"}
	col.AddMovie(m)

	loaded := col.GetMovie("Test", 200)
	if loaded == nil {
		t.Fatal("新影片应写入磁盘")
	}
	if loaded.Title != "新片" {
		t.Fatalf("期望 title=新片, 实际=%s", loaded.Title)
	}
}

func TestAddMovie_DetailUpdateOverwrites(t *testing.T) {
	dir := t.TempDir()
	col := NewCollector(dir)

	// 先写列表数据
	list := model.MovieInfo{VodId: 300, Source: "Test", Title: "测试", Score: ""}
	col.AddMovie(list)

	// 详情数据 (含 plays) 应覆盖
	detail := model.MovieInfo{
		VodId: 300, Source: "Test", Title: "测试", Score: "8.5",
		Plays: []model.PlayGroup{{From: "test", Urls: []model.PlayUrl{{Episode: "1", Url: "https://play"}}}},
	}
	col.AddMovie(detail)

	loaded := col.GetMovie("Test", 300)
	if loaded == nil {
		t.Fatal("读取失败")
	}
	if loaded.Score != "8.5" {
		t.Fatalf("期望 score=8.5, 实际=%s", loaded.Score)
	}
	if len(loaded.Plays) == 0 {
		t.Fatal("详情 plays 未写入")
	}
}

// ===== TRD: DetailBatch 消息测试 =====

func TestDetailBatch_Basic(t *testing.T) {
	src := SourceInfo{Name: "Test", Url: "http://test/"}
	batch := DetailBatch{
		Source: src,
		IDs:    []int{101, 102, 103},
	}
	if batch.Source.Name != "Test" {
		t.Fatalf("Source 不对")
	}
	if len(batch.IDs) != 3 {
		t.Fatalf("期望 3 个 ID, 实际 %d", len(batch.IDs))
	}
}
