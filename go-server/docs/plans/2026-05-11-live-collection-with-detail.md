# 实时采集+详情拉取 实现计划

> **目标：** 7个CMS源同时并行列表采集，每个源边采列表边触发详情拉取，数据不丢、不覆盖，4GB内存内高效运行

**架构：**
- 每个源一个 goroutine 独立跑列表采集 + 字节累加器
- 每累积 ~100KB 列表数据，把 ID 批量发送到详情管道
- 3 个详情 worker goroutine 持续从管道消费，并行拉取详情
- `AddMovie` 保护逻辑：列表数据不覆盖磁盘已有详情
- 内存索引 (~200MB) + 详情文件磁盘 (~2GB) ≈ 远低于 4GB

**文件变更：**
- `collector/collector.go` — 核心改造
- `collector/collector_test.go` — TDD 测试

---

### Task 1: 重写 collectList — 字节累加器 + 实时详情触发

**文件：**
- 修改: `collector/collector.go:304-358`
- 测试: `collector/collector_test.go`

**核心改动：**
- 每页 `data` 字节数累加到 `byteAccumulator`
- `byteAccumulator >= 100000` (~100KB) 时，收集本期 IDs 发射到 `detailCh`
- 支持 "首页即发" 和 "尾页补发"

- [ ] **Step 1: 在 `collector_test.go` 中编写单元测试**

```go
func TestByteAccumulator_TriggersAt100KB(t *testing.T) {
    src := SourceInfo{Name: "TestSrc", Url: "http://test.com/"}
    collectedIDs := make(map[int]bool)
    var triggeredBatches [][]int
    triggerFn := func(ids []int) {
        batch := make([]int, len(ids))
        copy(batch, ids)
        triggeredBatches = append(triggeredBatches, batch)
    }

    // 模拟积累 3 页，每页 ~40KB → 第3页触发 (40 * 3 = 120 >= 100)
    acc := newByteAccum(100000, collectedIDs, triggerFn)
    // 3批ID
    acc.add(1, 40000); collectedIDs[101] = true; collectedIDs[102] = true
    acc.add(2, 40000); collectedIDs[103] = true; collectedIDs[104] = true
    // 第3页触发
    acc.add(3, 40000); collectedIDs[105] = true; collectedIDs[106] = true

    if len(triggeredBatches) != 1 {
        t.Fatalf("期望1次触发, 实际 %d 次", len(triggeredBatches))
    }
    // 验证发射了前3页的ID
    triggerIDs := triggeredBatches[0]
    if len(triggerIDs) != 6 {
        t.Fatalf("期望6个ID, 实际 %d 个: %v", len(triggerIDs), triggerIDs)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /d F:\TtoV\player_server\go-server & set GONOSUMCHECK=* & set GONOSUMDB=* & D:\Go\bin\go test ./collector/ -run TestByteAccumulator -v`

Expected: undefined `newByteAccum`

- [ ] **Step 3: 实现 `byteAccum` 结构**

```go
// byteAccum 字节累加器 — 列表采集时累积字节数，超过阈值触发详情
type byteAccum struct {
    threshold    int            // 触发阈值 (字节)
    current      int            // 当前累积字节
    pending      map[int]bool   // 待发射的 ID 集合
    triggerFn    func([]int)    // 触发时回调
}

func newByteAccum(threshold int, pending map[int]bool, triggerFn func([]int)) *byteAccum {
    return &byteAccum{
        threshold: threshold,
        pending:   pending,
        triggerFn: triggerFn,
    }
}

func (b *byteAccum) add(vodId int, bytes int) {
    b.pending[vodId] = true
    b.current += bytes
    if b.current >= b.threshold {
        b.flush()
    }
}

func (b *byteAccum) flush() {
    if len(b.pending) == 0 { return }
    ids := make([]int, 0, len(b.pending))
    for id := range b.pending {
        ids = append(ids, id)
    }
    b.triggerFn(ids)
    b.pending = make(map[int]bool)
    b.current = 0
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: same command — Expected: PASS

- [ ] **Step 5: 重写 `collectList` 集成 byteAccum**

```go
// collectList 列表采集 — 每页累加字节，实时触发详情
func (c *Collector) collectList(src SourceInfo, detailCh chan<- message.DetailBatch) {
    baseUrl := src.Url
    if !strings.Contains(baseUrl, "?") {
        baseUrl += "?ac=list"
    } else {
        baseUrl += "&ac=list"
    }
    baseUrl += "&t=&at=json"

    maxPages := src.Pages
    if maxPages <= 0 { maxPages = 9999 }
    fmt.Printf("[%s] 列表: 开始, 最多 %d 页\n", src.Name, maxPages)

    pendingIDs := make(map[int]bool)
    accum := newByteAccum(100000, pendingIDs, func(ids []int) {
        detailCh <- message.DetailBatch{Source: src, IDs: ids}
    })

    consecutiveEmpty := 0
    consecutiveErr := 0
    for pg := 1; pg <= maxPages; pg++ {
        url := fmt.Sprintf("%s&pg=%d", baseUrl, pg)
        data, err := c.fetchURL(url)
        if err != nil {
            consecutiveErr++
            fmt.Printf("[%s] 列表: 第%d页失败 (连续%d次)\n", src.Name, pg, consecutiveErr)
            c.failed++
            if consecutiveErr >= 5 {
                fmt.Printf("[%s] 列表: 连续%d次失败, 放弃此源\n", src.Name, consecutiveErr)
                break
            }
            time.Sleep(500 * time.Millisecond)
            continue
        }
        consecutiveErr = 0
        movies, err := ParseMovieList(data, src.Name)
        if err != nil || len(movies) == 0 {
            consecutiveEmpty++
            if consecutiveEmpty >= EmptyLimit {
                fmt.Printf("[%s] 列表: 连续%d页为空, 结束\n", src.Name, EmptyLimit)
                break
            }
            time.Sleep(200 * time.Millisecond)
            continue
        }
        consecutiveEmpty = 0
        for _, m := range movies {
            c.AddMovie(m)
            accum.add(m.VodId, len(data)/len(movies)) // 均摊字节
        }
        if pg%10 == 0 || pg == 1 {
            fmt.Printf("[%s] 列表: 第%d页, 累计 %d 部\n", src.Name, pg, c.Stats().TotalMovies)
        }
        time.Sleep(150 * time.Millisecond)
    }
    // 尾页补发
    accum.flush()
    fmt.Printf("[%s] 列表: 完成\n", src.Name)
}
```

---

### Task 2: 抽取 DetailBatch 消息类型 + 重写详情管道

**文件：**
- 创建: `collector/message.go` — 消息类型定义
- 修改: `collector/collector.go` — 管道类型、detailWorker

- [ ] **Step 1: 在 `collector_test.go` 测试 DetailBatch 消息**

```go
func TestDetailBatchProcessesAllIDs(t *testing.T) {
    src := SourceInfo{Name: "Test", Url: "http://test/"}

    // 模拟一个详情批次
    batch := message.DetailBatch{
        Source: src,
        IDs:    []int{101, 102, 103},
    }

    if batch.Source.Name != "Test" {
        t.Fatalf("Source 不对")
    }
    if len(batch.IDs) != 3 {
        t.Fatalf("期望3个ID, 实际 %d", len(batch.IDs))
    }
}
```

- [ ] **Step 2: 创建 `collector/message.go`**

```go
package collector

// DetailBatch 详情批次消息 — 列表采集触发详情拉取
type DetailBatch struct {
    Source SourceInfo
    IDs    []int
}
```

- [ ] **Step 3: 重写 `CollectAll` + `detailWorker`**

```go
func (c *Collector) CollectAll(sources []SourceInfo) {
    // ... 相同前导逻辑 ...

    // 详情管道: 不再传 SourceInfo, 传 DetailBatch(具体ID列表)
    detailCh := make(chan DetailBatch, 256) // 大缓冲
    var detailWg sync.WaitGroup
    for i := 0; i < DetailWorkers; i++ {
        detailWg.Add(1)
        go func(id int) {
            defer detailWg.Done()
            c.detailWorker(id, detailCh)
        }(i)
    }

    // 列表 goroutine (不变)
    var listWg sync.WaitGroup
    for _, src := range sources { if src.Active {
        listWg.Add(1); src := src
        go func() { defer listWg.Done(); c.collectList(src, detailCh) }()
    }}
    listWg.Wait()
    close(detailCh)
    detailWg.Wait()
    // ... 完成输出 ...
}

func (c *Collector) detailWorker(id int, batchCh <-chan DetailBatch) {
    for batch := range batchCh {
        if len(batch.IDs) == 0 { continue }
        ids := batch.IDs
        src := batch.Source

        updated := 0
        // 按 DetailBatch 分批拉取 (CMS API 限制每批 ~20)
        for i := 0; i < len(ids); i += DetailBatch {
            end := i + DetailBatch
            if end > len(ids) { end = len(ids) }

            idStr := make([]string, end-i)
            for j, id := range ids[i:end] {
                idStr[j] = fmt.Sprintf("%d", id)
            }
            url := src.Url
            if strings.Contains(url, "?") {
                url += "&ac=detail&ids=" + strings.Join(idStr, ",")
            } else {
                url += "?ac=detail&ids=" + strings.Join(idStr, ",")
            }
            data, err := c.fetchURL(url)
            if err != nil { continue }

            movies, err := ParseMovieList(data, src.Name)
            if err != nil || len(movies) == 0 {
                if len(data) > 0 && data[0] == '{' {
                    var raw map[string]any
                    if json.Unmarshal(data, &raw) == nil && hasPlayField(raw) {
                        if m := objToMovie(raw, src.Name); m.VodId > 0 {
                            movies = []model.MovieInfo{m}
                        }
                    }
                }
            }
            for _, m := range movies {
                if m.VodId > 0 { c.AddMovie(m); updated++ }
            }
            time.Sleep(BatchDelay)
        }
        if updated > 0 {
            fmt.Printf("[W%d] %s: 详情更新 %d 部\n", id, src.Name, updated)
        }
    }
}
```

---

### Task 3: 集成测试 — 真实端到端流程

**文件：**
- 修改: `collector/collector_test.go`

- [ ] **Step 1: 测试实时详情不会覆盖磁盘已有数据**

```go
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
        Score: "", Year: 0, Area: "",
    }
    col.AddMovie(listData)

    // 3. 从磁盘读取, 验证 plays 还在
    loaded := col.GetMovie("Test", 100)
    if loaded == nil { t.Fatal("读取失败") }
    if len(loaded.Plays) == 0 {
        t.Fatal("列表数据覆盖了磁盘上的plays!")
    }
    if loaded.Score != "9.0" {
        t.Fatalf("期望 score=9.0, 实际=%s", loaded.Score)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Expected: 磁盘保护尚未实现 → 列表数据覆盖了详情

- [ ] **Step 3: 确认保护逻辑已生效**

当前 `AddMovie` 已有 `os.Stat` 保护，应该直接通过。

Expected: PASS

---

### Task 4: 内存限制保护

- [ ] **Step 1: 在 Collector 中添加内存计数**

```go
type Collector struct {
    // ... 原有字段 ...
    memBytes   atomic.Int64  // 内存使用量(字节)
}

func (c *Collector) AddMovie(m model.MovieInfo) {
    // ... 现有逻辑 ...
    // 索引大小: 每条约 200 bytes
    c.memBytes.Add(200)
}

func (c *Collector) Stats() model.CollectStats {
    // 在返回中追加内存信息
    return model.CollectStats{
        // ... 原有 ...
        MemoryMB: int(c.memBytes.Load() / 1024 / 1024),
    }
}
```

- [ ] **Step 2: 在 CollectAll 中添加内存限制检查**

```go
// 列表循环中:
if c.memBytes.Load() > maxMemoryBytes {
    fmt.Printf("[%s] 列表: 内存超限 %d MB, 暂停10秒\n", src.Name, c.memBytes.Load()/1024/1024)
    time.Sleep(10 * time.Second)
}
```

---

### 内存预算

| 组件 | 大小 | 说明 |
|:-----|:-----|:------|
| MovieIndex (内存) | 65万 × 200B = **130MB** | 轻量索引 |
| detailCh 缓冲 | 256 × 1KB = **0.3MB** | 消息队列 |
| HTTP 连接池 | 50 连接 × 64KB = **3MB** | 传输缓冲 |
| Go GC 开销 | ~150MB | 通常为活跃对象的 1-2 倍 |
| **总计** | **~300MB** << **4GB** | ✅ |

---
