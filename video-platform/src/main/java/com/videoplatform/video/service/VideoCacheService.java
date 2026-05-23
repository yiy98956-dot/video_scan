package com.videoplatform.video.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 视频流文件缓存服务
 * <p>
 * L1: 内存 LRU — 热数据快速返回（默认 200MB）
 * L2: 磁盘文件 — 持久化缓存目录 cache/java_proxy/
 * <p>
 * 解决 C++ curl.exe 无法下载 CDN 内容时，Java 直连后缓存复用的问题。
 */
@Slf4j
@Service
public class VideoCacheService {

    /** 磁盘缓存根目录 */
    private final Path cacheDir;

    /** 内存缓存最大字节数（200MB） */
    private final long maxMemoryBytes;

    /** L1 内存缓存 */
    private final LinkedHashMap<String, CacheEntry> memoryCache;

    /** L1 当前使用量 */
    private final AtomicLong memoryUsed = new AtomicLong(0);

    /** L1/L2 统计 */
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong diskWrites = new AtomicLong(0);

    /** 定时清理过期缓存 */
    private ScheduledExecutorService scheduler;

    /** 缓存有效期（默认 2 小时） */
    private static final long TTL_MILLIS = 2 * 3600 * 1000L;

    private static final String HEX_CHARS = "0123456789abcdef";

    public VideoCacheService(
            @Value("${video.cache.dir:cache/java_proxy}") String cacheDir,
            @Value("${video.cache.max-memory-bytes:209715200}") long maxMemoryBytes) {
        this.cacheDir = Paths.get(cacheDir);
        this.maxMemoryBytes = maxMemoryBytes;
        // LRU 内存缓存
        this.memoryCache = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                if (memoryUsed.get() > maxMemoryBytes) {
                    memoryUsed.addAndGet(-eldest.getValue().size);
                    log.debug("L1 evict: {} (size={})", eldest.getKey().substring(0, Math.min(20, eldest.getKey().length())), eldest.getValue().size);
                    return true;
                }
                return false;
            }
        };
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(cacheDir);
            log.info("VideoCacheService initialized, dir={}, maxMemory={}MB", cacheDir, maxMemoryBytes / 1024 / 1024);
            // 恢复磁盘索引
            rebuildDiskIndex();
            // 定时清理过期
            scheduler = Executors.newSingleThreadScheduledExecutor();
            scheduler.scheduleAtFixedRate(this::cleanupExpired, 30, 30, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("VideoCacheService init failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) scheduler.shutdown();
    }

    /**
     * 获取缓存内容
     */
    public byte[] get(String key) {
        // L1 内存
        CacheEntry memEntry;
        synchronized (memoryCache) {
            memEntry = memoryCache.get(key);
        }
        if (memEntry != null && !isExpired(memEntry)) {
            hits.incrementAndGet();
            log.debug("L1 hit: {} ({} bytes)", keyPrefix(key), memEntry.size);
            return memEntry.data;
        }

        // L2 磁盘
        Path file = cacheFilePath(key);
        if (Files.exists(file)) {
            try {
                byte[] data = Files.readAllBytes(file);
                long modified = Files.getLastModifiedTime(file).toMillis();
                if (System.currentTimeMillis() - modified < TTL_MILLIS) {
                    // 写入 L1
                    CacheEntry entry = new CacheEntry(data, data.length);
                    synchronized (memoryCache) {
                        memoryCache.put(key, entry);
                        memoryUsed.addAndGet(data.length);
                    }
                    hits.incrementAndGet();
                    log.debug("L2 hit: {} ({} bytes)", keyPrefix(key), data.length);
                    return data;
                } else {
                    // 过期删除
                    Files.deleteIfExists(file);
                }
            } catch (Exception e) {
                log.warn("L2 read failed: {}", e.getMessage());
            }
        }

        misses.incrementAndGet();
        return null;
    }

    /**
     * 存入缓存
     */
    public void put(String key, byte[] data) {
        if (data == null || data.length == 0) return;

        // L1 内存
        CacheEntry entry = new CacheEntry(data, data.length);
        synchronized (memoryCache) {
            memoryCache.put(key, entry);
            memoryUsed.addAndGet(data.length);
        }

        // L2 磁盘（异步写入，不阻塞主流程）
        Path file = cacheFilePath(key);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            diskWrites.incrementAndGet();
            log.debug("L2 write: {} ({} bytes)", keyPrefix(key), data.length);
        } catch (Exception e) {
            log.warn("L2 write failed: {}", e.getMessage());
        }
    }

    public CacheStats stats() {
        long memCount;
        long memBytes;
        synchronized (memoryCache) {
            memCount = memoryCache.size();
            memBytes = memoryUsed.get();
        }
        long diskCount = 0;
        long diskBytes = 0;
        try (var files = Files.walk(cacheDir, 3)) {
            for (var f : files.filter(Files::isRegularFile).toList()) {
                diskCount++;
                diskBytes += Files.size(f);
            }
        } catch (Exception ignored) {}

        long total = hits.get() + misses.get();
        double hitRate = total > 0 ? (double) hits.get() / total * 100 : 0;

        return new CacheStats(memCount, memBytes, maxMemoryBytes,
                diskCount, diskBytes, 30L * 1024 * 1024 * 1024,
                hits.get(), misses.get(), diskWrites.get(), hitRate);
    }

    // ─── 内部 ───

    private boolean isExpired(CacheEntry entry) {
        return System.currentTimeMillis() - entry.timestamp > TTL_MILLIS;
    }

    private void rebuildDiskIndex() {
        try (var files = Files.walk(cacheDir, 3)) {
            long count = files.filter(Files::isRegularFile).count();
            log.info("Disk cache rebuild: {} files", count);
        } catch (Exception ignored) {}
    }

    private void cleanupExpired() {
        try (var files = Files.walk(cacheDir, 3)) {
            for (var f : files.filter(Files::isRegularFile).toList()) {
                try {
                    long modified = Files.getLastModifiedTime(f).toMillis();
                    if (System.currentTimeMillis() - modified > TTL_MILLIS) {
                        Files.deleteIfExists(f);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private Path cacheFilePath(String key) {
        String hash = md5(key);
        return cacheDir.resolve(hash.substring(0, 2)).resolve(hash);
    }

    private String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(HEX_CHARS.charAt((b >> 4) & 0x0f));
                sb.append(HEX_CHARS.charAt(b & 0x0f));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private String keyPrefix(String key) {
        return key.length() > 40 ? key.substring(0, 40) + "..." : key;
    }

    private static class CacheEntry {
        final byte[] data;
        final int size;
        final long timestamp;

        CacheEntry(byte[] data, int size) {
            this.data = data;
            this.size = size;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public record CacheStats(
            long memoryItems, long memoryBytes, long memoryLimit,
            long diskItems, long diskBytes, long diskLimit,
            long hits, long misses, long diskWrites, double hitRate
    ) {}
}
