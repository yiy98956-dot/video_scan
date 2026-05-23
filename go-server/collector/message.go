package collector

// DetailBatch 详情批次消息 — 列表采集触发详情拉取
type DetailBatch struct {
	Source SourceInfo
	IDs    []int
}

// byteAccum 字节累加器 — 列表采集时累积字节数，超过阈值触发详情
type byteAccum struct {
	threshold int          // 触发阈值 (字节)
	current   int          // 当前累积字节
	pending   map[int]bool // 待发射的 ID 集合
	triggerFn func([]int)  // 触发时回调
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
	if len(b.pending) == 0 {
		return
	}
	ids := make([]int, 0, len(b.pending))
	for id := range b.pending {
		ids = append(ids, id)
	}
	b.triggerFn(ids)
	b.pending = make(map[int]bool)
	b.current = 0
}
