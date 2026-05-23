// Go-PlayerServer
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"server.app/cache"
	"server.app/category"
	"server.app/collector"
	"server.app/model"
	"server.app/monitor"
	"server.app/search"
	"server.app/server"
)

const (
	MEMORY_CACHE = 200 * 1024 * 1024
	DISK_CACHE   = 20 * 1024 * 1024 * 1024
	PORT         = 54567
)

func exeDir() string {
	if pwd := os.Getenv("PWD"); pwd != "" {
		return pwd
	}
	exe, err := os.Executable()
	if err != nil {
		return "."
	}
	return filepath.Dir(exe)
}

func main() {
	baseDir := exeDir()
	dataDir := filepath.Join(baseDir, "data", "movies")
	configFile := filepath.Join(baseDir, "config", "sources.json")
	cacheDir := filepath.Join(baseDir, "cache")
	logDir := filepath.Join(baseDir, "logs")

	fmt.Println("═══════════════════════════════════════════")
	fmt.Println("  Go-PlayerServer v1.0.0")
	fmt.Printf("  数据目录: %s\n", dataDir)
	fmt.Printf("  监听端口: %d\n", PORT)
	fmt.Println("═══════════════════════════════════════════")

	os.MkdirAll(logDir, 0755)
	logFile, err := os.OpenFile(filepath.Join(logDir, "server.log"), os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err == nil {
		slog.SetDefault(slog.New(slog.NewTextHandler(io.MultiWriter(os.Stdout, logFile), &slog.HandlerOptions{Level: slog.LevelInfo})))
	} else {
		slog.SetDefault(slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo})))
	}
	fmt.Println("[OK] 日志初始化完成")

	sources := loadSources(configFile)
	fmt.Printf("[OK] 配置加载完成: %d 个采集源\n", len(sources))

	os.MkdirAll(cacheDir, 0755)
	proxyCache := cache.New(MEMORY_CACHE, DISK_CACHE, cacheDir)
	fmt.Printf("[OK] 缓存初始化: 内存 %dGB + 磁盘 %dGB\n", MEMORY_CACHE/1024/1024/1024, DISK_CACHE/1024/1024/1024)

	col := collector.NewCollector(dataDir)
	col.LoadAll()

	// 分类系统初始化 - 修复：只调用 Init()
	category.Init()
	fmt.Println("[OK] 分类系统就绪")

	col.ReclassifyAll()
	fmt.Println("[OK] 存量数据重分类完成")
	movieCount := len(col.AllMovies())
	fmt.Printf("[OK] 数据加载: %d 部影片\n", movieCount)

	se := search.NewEngine()
	fmt.Printf("[OK] 搜索引擎就绪\n")

	mon := monitor.Get()
	liveGroups := loadLiveChannels(configFile)
	fmt.Printf("[OK] 直播频道加载: %d 个频道\n", len(liveGroups)*20)

	srv := &server.Server{
		Cache:      proxyCache,
		Collector:  col,
		Search:     se,
		Monitor:    mon,
		Sources:    sources,
		LiveGroups: liveGroups,
		DataDir:    dataDir,
	}

	mux := http.NewServeMux()
	srv.RegisterRoutes(mux)
	handler := corsMiddleware(mux)

	httpServer := &http.Server{
		Addr:         fmt.Sprintf(":%d", PORT),
		Handler:      handler,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 60 * time.Second,
		IdleTimeout:  120 * time.Second,
	}

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-quit
		fmt.Println("\n[SHUTDOWN] 正在关闭服务器...")
		httpServer.Close()
	}()

	fmt.Printf("\n  管理面板: http://localhost:%d\n", PORT)
	fmt.Printf("  状态接口: http://localhost:%d/api/status\n", PORT)
	fmt.Println("═══════════════════════════════════════════")
	if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		fmt.Fprintf(os.Stderr, "[ERROR] 服务器启动失败: %v\n", err)
		os.Exit(1)
	}
}

type Config struct {
	Sources []configSource      `json:"cms_sources"`
	Live    []model.LiveChannel `json:"live_channels"`
}

type configSource struct {
	Name   string `json:"name"`
	Url    string `json:"url"`
	Pages  int    `json:"pages"`
	Active bool   `json:"active"`
}

func loadSources(path string) []collector.SourceInfo {
	data, err := os.ReadFile(path)
	if err != nil {
		slog.Warn("Cannot read config", "path", path)
		return nil
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		slog.Warn("Cannot parse config", "error", err)
		return nil
	}
	var result []collector.SourceInfo
	for _, s := range cfg.Sources {
		result = append(result, collector.SourceInfo{
			Name: s.Name, Url: s.Url, Pages: s.Pages, Active: s.Active,
		})
	}
	slog.Info("Sources loaded", "count", len(result))
	return result
}

func loadLiveChannels(path string) []server.LiveGroup {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil
	}
	groups := make(map[string][]model.LiveChannel)
	for _, ch := range cfg.Live {
		if ch.Group == "" {
			ch.Group = "未分组"
		}
		groups[ch.Group] = append(groups[ch.Group], ch)
	}
	var result []server.LiveGroup
	for name, channels := range groups {
		result = append(result, server.LiveGroup{Name: name, Channels: channels})
	}
	return result
}

func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
		if r.Method == "OPTIONS" {
			w.WriteHeader(204)
			return
		}
		next.ServeHTTP(w, r)
	})
}
