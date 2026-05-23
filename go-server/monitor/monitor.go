package monitor

import (
	"math"
	"sync"
	"sync/atomic"
	"time"

	"server.app/model"
)

type Monitor struct {
	mu          sync.RWMutex
	startTime   time.Time
	requests    atomic.Uint64
	bytesIn     atomic.Uint64
	bytesOut    atomic.Uint64
	cacheHits   atomic.Uint64
	cacheMisses atomic.Uint64
	evictions   atomic.Uint64
	memUsed     atomic.Uint64
	diskUsed    atomic.Uint64
	endpoints   map[string]*endpointStat
	users       atomic.Int64
	history     []model.TrafficPoint
	maxHistory  int
}

type endpointStat struct {
	mu        sync.Mutex
	count     uint64
	lastCall  uint64
	bytesOut  uint64
}

var global = New()

func New() *Monitor {
	return &Monitor{
		startTime:  time.Now(),
		endpoints:  make(map[string]*endpointStat),
		maxHistory: 300,
	}
}

func Get() *Monitor { return global }

func (m *Monitor) OnRequest()     { m.requests.Add(1) }
func (m *Monitor) OnBytesIn(n int64)  { m.bytesIn.Add(uint64(n)) }
func (m *Monitor) OnBytesOut(n int64) { m.bytesOut.Add(uint64(n)) }
func (m *Monitor) OnCacheHit()    { m.cacheHits.Add(1) }
func (m *Monitor) OnCacheMiss()   { m.cacheMisses.Add(1) }
func (m *Monitor) OnEviction()    { m.evictions.Add(1) }
func (m *Monitor) SetMemoryUsage(n uint64) { m.memUsed.Store(n) }
func (m *Monitor) SetDiskUsage(n uint64)   { m.diskUsed.Store(n) }
func (m *Monitor) AddUser(n int64)         { m.users.Add(n) }

func (m *Monitor) OnEndpointCall(route string, bytesOut int64) {
	m.OnRequest()
	m.OnBytesOut(bytesOut)
	m.mu.Lock()
	es, ok := m.endpoints[route]
	if !ok {
		es = &endpointStat{}
		m.endpoints[route] = es
	}
	m.mu.Unlock()
	es.mu.Lock()
	es.count++
	es.lastCall = uint64(time.Now().UnixMilli())
	es.bytesOut += uint64(bytesOut)
	es.mu.Unlock()
}

func (m *Monitor) Snapshot() model.ServerSnapshot {
	uptime := uint64(time.Since(m.startTime).Seconds())
	hits := m.cacheHits.Load()
	misses := m.cacheMisses.Load()
	total := hits + misses
	var rate float64
	if total > 0 {
		rate = float64(hits) / float64(total) * 100
	}
	mem := m.memUsed.Load()
	disk := m.diskUsed.Load()

	return model.ServerSnapshot{
		UptimeSeconds:  uptime,
		ActiveUsers:    uint64(max(0, m.users.Load())),
		TotalRequests:  m.requests.Load(),
		TotalBytesIn:   m.bytesIn.Load(),
		TotalBytesOut:  m.bytesOut.Load(),
		CacheHits:      hits,
		CacheMisses:    misses,
		CacheEvictions: m.evictions.Load(),
		CacheHitRate:   math.Round(rate*10) / 10,
		MemoryLimitGB:  4,
		MemoryUsedGB:   float64(mem) / float64(1<<30),
		DiskLimitGB:    30,
		DiskUsedGB:     float64(disk) / float64(1<<30),
		Version:        "go-v1.0.0",
	}
}

func (m *Monitor) Endpoints() map[string]uint64 {
	m.mu.RLock()
	eps := make(map[string]*endpointStat, len(m.endpoints))
	for k, v := range m.endpoints {
		eps[k] = v
	}
	m.mu.RUnlock()
	result := make(map[string]uint64)
	for k, v := range eps {
		v.mu.Lock()
		result[k] = v.count
		v.mu.Unlock()
	}
	return result
}
