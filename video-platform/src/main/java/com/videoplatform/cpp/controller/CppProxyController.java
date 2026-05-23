package com.videoplatform.cpp.controller;

import com.videoplatform.infrastructure.common.R;
import com.videoplatform.video.client.CppVideoClient;
import com.videoplatform.video.service.VideoCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Go PlayerServer 代理控制器
 * <p>
 * 前端只调 /api/go/*，Java 内部用 CppVideoClient 转发到 Go (:54567)。
 * 视频流代理流程：
 *   - m3u8: Java → Go proxy → CDN（带 Java 缓存 + URL 重写）
 *   - ts/mp4: 302 重定向到 Go proxy（不经过 Java 内存缓冲，消除卡顿）
 *     前端 JS 只调 /api/go/proxy，浏览器自动跟随 302 到 Go proxy，数据 Go → 浏览器直达
 */
@Slf4j
@Tag(name = "Go 代理", description = "转发前端请求到 Go PlayerServer（状态/直播/采集/缓存）")
@RestController
@RequestMapping("/api/go")
@RequiredArgsConstructor
public class CppProxyController {

    private static final String CPP_PROXY_PREFIX = "/api/movies/proxy";
    private static final String OUR_PROXY_PREFIX = "/api/go/proxy";

    private final CppVideoClient cppClient;
    private final VideoCacheService videoCacheService;

    @org.springframework.beans.factory.annotation.Value("${cpp.service.base-url:http://localhost:54567}")
    private String cppBaseUrl;

    // ─── 服务器状态 ───

    @Operation(summary = "C++ 服务器状态")
    @GetMapping("/status")
    public R<Object> status() {
        return jsonResult(cppClient.fetchRaw("/api/status"));
    }

    // ─── 缓存详情 ───

    @Operation(summary = "C++ 缓存详情")
    @GetMapping("/cache")
    public R<Object> cache() {
        return jsonResult(cppClient.fetchRaw("/api/cache"));
    }

    // ─── 端点监控 ───

    @Operation(summary = "C++ 端点监控")
    @GetMapping("/endpoints")
    public R<Object> endpoints() {
        return jsonResult(cppClient.fetchRaw("/api/monitor/endpoints"));
    }

    // ─── 采集管理 ───

    @Operation(summary = "采集状态")
    @GetMapping("/collect/status")
    public R<Object> collectStatus() {
        return jsonResult(cppClient.fetchRaw("/api/collect/status"));
    }

    @Operation(summary = "触发采集")
    @GetMapping("/collect/run")
    public R<Object> collectRun() {
        return jsonResult(cppClient.fetchRaw("/api/collect/run"));
    }

    @Operation(summary = "重置并重新采集")
    @GetMapping("/collect/reset")
    public R<Object> collectReset() {
        return jsonResult(cppClient.fetchRaw("/api/collect/reset"));
    }

    // ─── 直播 ───

    @Operation(summary = "直播分组列表")
    @GetMapping("/live/groups")
    public R<Object> liveGroups() {
        return jsonResult(cppClient.fetchRaw("/api/live/groups"));
    }

    @Operation(summary = "直播播放地址")
    @GetMapping("/live/play")
    public R<Object> livePlay(@RequestParam String name) {
        return jsonResult(cppClient.fetchRaw("/api/live/play?name=" + name));
    }

    // ─── 视频流代理 ───

    @Operation(summary = "视频流代理",
            description = "ts/mp4 片段直连；m3u8 先查 Java 缓存，未命中则 C++ → 直连 → 多种 Referer → 兜底 302 重定向到原始CDN")
    @GetMapping("/proxy")
    public ResponseEntity<?> proxy(@RequestParam String url) {
        try {
            log.info("[proxy] request for: {}", url.substring(0, Math.min(80, url.length())));

            String cacheKey = "m3u8:" + url;

            // ── 1. Java 缓存 ──
            byte[] cached = videoCacheService.get(cacheKey);
            if (cached != null) {
                log.info("[proxy] cache hit: {} ({} bytes)", url.substring(0, Math.min(50, url.length())), cached.length);
                return buildResponse(cached, url);
            }

            // ── 2. ts/mp4 视频片段：Java 直连优先（Go proxy 被封时不卡顿） ──
            //    走通后不缓存（片段太多，避免撑爆缓存）
            if (isVideoSegment(url)) {
                byte[] data = fetchWithFallback(url);
                if (data != null && data.length > 0) {
                    log.info("[proxy] direct for segment: {} ({} bytes)", url.substring(0, Math.min(50, url.length())), data.length);
                    return buildResponse(data, url);
                }
                // 直连失败 → 302 到 Go proxy 兜底
                log.warn("[proxy] direct failed for segment, 302 to Go: {}", url.substring(0, Math.min(60, url.length())));
                String goProxyUrl = cppBaseUrl + CPP_PROXY_PREFIX + "?url=" + java.net.URLEncoder.encode(url, StandardCharsets.UTF_8)
                        + "&ref=" + java.net.URLEncoder.encode(urlBase(url), StandardCharsets.UTF_8);
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(goProxyUrl))
                        .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                        .build();
            }

            // ── 3. m3u8 清单：C++ proxy → 直连 → 多种 Referer ──

            // A. C++ proxy — 桌面 UA（Go 内部会先试 iOS UA 再重试桌面 UA）
            byte[] data = cppClient.fetchRawBytes(CPP_PROXY_PREFIX + "?url=" + java.net.URLEncoder.encode(url, StandardCharsets.UTF_8)
                    + "&ref=" + java.net.URLEncoder.encode("https://player.bdzybf11.com/", StandardCharsets.UTF_8)
                    + "&ua=" + java.net.URLEncoder.encode("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36", StandardCharsets.UTF_8));
            String rawStr = data != null ? new String(data, StandardCharsets.UTF_8) : "";
            if (data != null && data.length > 20 && !rawStr.startsWith("ERROR:")) {
                log.info("[proxy] C++ proxy success, caching ({} bytes)", data.length);
                videoCacheService.put(cacheKey, data);
                return buildResponse(data, url);
            }

            // B. C++ proxy — iOS UA 重试（部分 CDN 只认 iPhone 请求）
            data = cppClient.fetchRawBytes(CPP_PROXY_PREFIX + "?url=" + java.net.URLEncoder.encode(url, StandardCharsets.UTF_8)
                    + "&ref=" + java.net.URLEncoder.encode(urlBase(url), StandardCharsets.UTF_8)
                    + "&ua=" + java.net.URLEncoder.encode("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15", StandardCharsets.UTF_8));
            rawStr = data != null ? new String(data, StandardCharsets.UTF_8) : "";
            if (data != null && data.length > 20 && !rawStr.startsWith("ERROR:")) {
                log.info("[proxy] C++ proxy (iOS UA) success, caching ({} bytes)", data.length);
                videoCacheService.put(cacheKey, data);
                return buildResponse(data, url);
            }

            // C. Java 直连（多种 Referer）
            data = fetchWithFallback(url);
            if (data != null) {
                log.info("[proxy] direct success, caching ({} bytes)", data.length);
                videoCacheService.put(cacheKey, data);
                return buildResponse(data, url);
            }

            // D. 全部失败 → 302 重定向到 Go proxy 兜底
            log.warn("[proxy] ALL proxy methods failed, 302 to Go proxy: {}", url);
            String goProxyUrl = cppBaseUrl + CPP_PROXY_PREFIX + "?url=" + java.net.URLEncoder.encode(url, StandardCharsets.UTF_8)
                    + "&ref=" + java.net.URLEncoder.encode(urlBase(url), StandardCharsets.UTF_8);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(goProxyUrl))
                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    .build();
        } catch (Exception e) {
            log.error("[proxy] UNEXPECTED ERROR for {}: {}: {}", url, e.getClass().getSimpleName(), e.getMessage(), e);
            return ResponseEntity.status(502).body(("proxy error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 快速尝试直连（8 秒超时，含 SSL 握手），CDN 连不上就立刻放弃，走 302 兜底
     */
    private byte[] fetchWithFallback(String url) {
        byte[] data = cppClient.fetchDirectBytes(url, urlBase(url), 8);
        if (data != null && data.length > 0) return data;
        return null;
    }

    /**
     * 判断是否为 ts/mp4 等视频片段（不走 C++ proxy，直接返回）
     * CDN 可能伪装后缀如 .jpge，按扩展名规则严格匹配
     */
    private boolean isVideoSegment(String url) {
        String lower = url.toLowerCase();
        return lower.contains(".ts") || lower.contains(".mp4")
                || lower.contains(".m4s") || lower.contains(".flv")
                || lower.contains(".webm") || lower.contains(".jpge")
                || lower.contains("seg-")
                || lower.matches(".*[0-9]+\\.[0-9a-z]{3,4}$");
    }

    private ResponseEntity<byte[]> buildResponse(byte[] data, String originalUrl) {
        String contentType = detectContentType(originalUrl, new String(data, StandardCharsets.UTF_8));

        // 如果是 m3u8，重写所有 URL 指向我们自己的 proxy
        if (contentType.contains("mpegurl") || contentType.contains("m3u8")) {
            String rewritten = rewriteM3u8(new String(data, StandardCharsets.UTF_8), originalUrl);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                    .body(rewritten.getBytes(StandardCharsets.UTF_8));
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(data);
    }

    /**
     * 重写 m3u8 内容中的所有 URL，相对路径转为绝对再包装为 /api/go/proxy
     * 逻辑完全对齐 C++ main_headless.cpp 第 220-227 行的处理
     */
    private String rewriteM3u8(String m3u8, String originalUrl) {
        String baseUrl = urlBase(originalUrl);
        String baseHost = urlHostBase(originalUrl);
        StringBuilder result = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new StringReader(m3u8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    result.append(line).append("\n");
                    continue;
                }

                // 注释/标记行：可能需要重写 URI="" 中的 URL
                if (trimmed.charAt(0) == '#') {
                    String processed = line;
                    int idx = 0;
                    while ((idx = processed.indexOf("URI=\"", idx)) != -1) {
                        int start = idx + 5;
                        int end = processed.indexOf('"', start);
                        if (end == -1) break;
                        String origUri = processed.substring(start, end);
                        String newUri = makeProxyUrl(origUri, baseUrl, baseHost);
                        processed = processed.substring(0, start) + newUri + processed.substring(end);
                        idx = start + newUri.length();
                    }
                    result.append(processed).append("\n");
                    continue;
                }

                // URL 行：重写为 proxy URL
                String newUrl = makeProxyUrl(trimmed, baseUrl, baseHost);
                result.append(newUrl).append("\n");
            }
        } catch (Exception e) {
            log.warn("rewriteM3u8 failed, returning original: {}", e.getMessage());
            return m3u8;
        }

        String r = result.toString();
        if (!r.startsWith("#EXTM3U")) r = "#EXTM3U\n" + r;
        return r;
    }

    /**
     * 将 m3u8 中的 URL 转为 /api/go/proxy 格式（通过 Java proxy 访问，带正确 Referer）
     * <p>
     * 所有 URL 都走代理，因为浏览器直接请求 CDN 时 Referer 不对（localhost），CDN 拒绝。
     * Java proxy 会设置 Referer 为 CDN 域名，CDN 信任。
     */
    private String makeProxyUrl(String url, String baseUrl, String baseHost) {
        // 已经是 proxy URL 则不做二次包装
        if (url.startsWith(OUR_PROXY_PREFIX) || url.startsWith(CPP_PROXY_PREFIX)) return url;

        String absoluteUrl;
        if (url.contains("://")) {
            absoluteUrl = url;
        } else if (url.startsWith("/")) {
            absoluteUrl = baseHost + url.substring(1);
        } else {
            absoluteUrl = baseUrl + url;
        }

        return OUR_PROXY_PREFIX + "?url=" + java.net.URLEncoder.encode(absoluteUrl, StandardCharsets.UTF_8);
    }

    /**
     * 从 URL 中提取协议 + 主机 + 路径前缀（不含文件名）
     * e.g. https://cdn.com/a/b/c.m3u8 → https://cdn.com/a/b/
     */
    private String urlBase(String url) {
        int p = url.indexOf("://");
        if (p == -1) return "";
        int hs = p + 3;
        int ls = url.lastIndexOf('/');
        if (ls <= hs) return url + "/";
        return url.substring(0, ls + 1);
    }

    /**
     * 从 URL 中提取协议 + 主机 (末尾带 /)
     * e.g. https://cdn.com/a/b/c.m3u8 → https://cdn.com/
     */
    private String urlHostBase(String url) {
        int p = url.indexOf("://");
        if (p == -1) return "";
        int hs = p + 3;
        int sl = url.indexOf('/', hs);
        if (sl == -1) return url + "/";
        return url.substring(0, sl + 1);
    }

    private String detectContentType(String url, String body) {
        if (url.contains(".m3u8") || body.contains("#EXTM3U")) return "application/vnd.apple.mpegurl";
        if (url.contains(".ts")) return "video/MP2T";
        if (url.contains(".mp4")) return "video/mp4";
        if (url.contains(".jpg") || url.contains(".jpeg")) return "image/jpeg";
        if (url.contains(".png")) return "image/png";
        if (url.contains(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    // ─── 健康检查 ───

    @Operation(summary = "健康检查")
    @GetMapping("/health")
    public R<Object> health() {
        return jsonResult(cppClient.fetchRaw("/api/health"));
    }

    // ─── 搜索自动补全 ───

    @Operation(summary = "搜索自动补全")
    @GetMapping("/movies/autocomplete")
    public R<Object> autocomplete(@RequestParam String q, @RequestParam(defaultValue = "10") int size) {
        return jsonResult(cppClient.fetchRaw("/api/movies/autocomplete?q=" + q + "&size=" + size));
    }

    // ─── 电影类型列表 ───

    @Operation(summary = "电影类型列表")
    @GetMapping("/movies/types")
    public R<Object> movieTypes() {
        return jsonResult(cppClient.fetchRaw("/api/movies/types"));
    }

    // ─── Java 视频缓存统计 ───

    @Operation(summary = "Java 视频代理缓存统计")
    @GetMapping("/cache-stats")
    public R<VideoCacheService.CacheStats> cacheStats() {
        return R.success(videoCacheService.stats());
    }

    // ─── 工具 ───

    private R<Object> jsonResult(String raw) {
        if (raw == null) return R.error("C++ 服务连接失败");
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return R.success(mapper.readTree(raw));
        } catch (Exception e) {
            return R.success(raw);
        }
    }
}
