package com.videoplatform.cpp.controller;

import com.videoplatform.video.client.CppVideoClient;
import com.videoplatform.video.service.VideoCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 封面图片代理
 * <p>
 * Go 把封面 URL 改写为 /api/movies/proxy?url=原始CDN地址，
 * 浏览器请求到 Java（8080），Java 用 WebClient 带 Referer 直连 CDN 拉取图片。
 */
@Slf4j
@Tag(name = "封面图片代理", description = "WebClient 带 Referer 直连 CDN 拉取封面图")
@RestController
@RequiredArgsConstructor
public class ImageProxyController {

    private final CppVideoClient cppClient;
    private final VideoCacheService videoCacheService;

    @org.springframework.beans.factory.annotation.Value("${cpp.service.base-url:http://localhost:54567}")
    private String cppBaseUrl;

    /** 短时间内请求失败的 URL，不再重复请求，避免雪崩 */
    private final Set<String> failedUrls = ConcurrentHashMap.newKeySet();

    /** 失败标记过期时间：5 分钟后重试 */
    private static final long FAIL_TTL_MS = 5 * 60 * 1000;

    /** 记录失败时间，用于过期判断 */
    private final ConcurrentHashMap<String, Long> failedAt = new ConcurrentHashMap<>();

    @Operation(summary = "封面图片代理",
            description = "Java WebClient 带 Referer 直连 CDN，内存+磁盘双缓存，失败降级")
    @GetMapping("/api/movies/proxy")
    public ResponseEntity<?> proxy(@RequestParam String url) {
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body("missing url".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        try {
            String cacheKey = "img:" + url;

            // 0. 检查是否在失败黑名单中（5分钟内不再重试）
            if (failedUrls.contains(url)) {
                Long failTime = failedAt.get(url);
                if (failTime != null && (System.currentTimeMillis() - failTime) < FAIL_TTL_MS) {
                    log.debug("[img-proxy] skip failed url (cooldown): {}", url.substring(0, Math.min(60, url.length())));
                    return buildPlaceholderResponse();
                } else {
                    // 过期，移除失败标记，允许重试
                    failedUrls.remove(url);
                    failedAt.remove(url);
                }
            }

            // 1. 查缓存
            byte[] cached = videoCacheService.get(cacheKey);
            if (cached != null && cached.length > 0) {
                log.debug("[img-proxy] cache hit: {} ({} bytes)", url.substring(0, Math.min(50, url.length())), cached.length);
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(detectContentType(url)))
                        .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                        .body(cached);
            }

            // 2. WebClient 直连 CDN（尝试多种 Referer）
            byte[] data = null;
            String[] referers = {
                    extractReferer(url),                          // 原始域名
                    "https://www.mdzywtupian.com/",               // MoDu 图片源
                    "https://ok.zuidapic.com/",                   // ZuiDa 图片源
                    "https://player.bdzybf11.com/",               // 通用 fallback
                    ""                                            // 无 Referer
            };
            for (String ref : referers) {
                if (ref == null) continue;
                data = cppClient.fetchDirectBytes(url, ref, 10);  // 10秒超时
                if (data != null && data.length > 0) break;
            }

            if (data == null || data.length == 0) {
                // 2a. 直连失败，尝试 Go proxy（Go 有重试 + 多 UA 机制）
                log.debug("[img-proxy] direct failed, trying Go proxy: {}", url.substring(0, Math.min(50, url.length())));
                try {
                    String goProxyUrl = cppBaseUrl + "/api/movies/proxy?url=" + java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8);
                    data = cppClient.fetchRawBytes(goProxyUrl);
                } catch (Exception e) {
                    log.debug("[img-proxy] Go proxy also failed: {}", e.getMessage());
                }
            }

            if (data == null || data.length == 0) {
                log.warn("[img-proxy] all attempts failed for: {}", url);
                markFailed(url);
                return buildPlaceholderResponse();
            }

            // 3. 缓存后返回
            videoCacheService.put(cacheKey, data);
            log.debug("[img-proxy] success: {} ({} bytes)", url.substring(0, Math.min(50, url.length())), data.length);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(detectContentType(url)))
                    .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                    .body(data);
        } catch (Exception e) {
            log.warn("[img-proxy] error for {}: {}", url.substring(0, Math.min(60, url.length())), e.getMessage());
            markFailed(url);
            return buildPlaceholderResponse();
        }
    }

    /**
     * 标记 URL 为失败（5分钟内不再重试）
     */
    private void markFailed(String url) {
        failedUrls.add(url);
        failedAt.put(url, System.currentTimeMillis());
        // 定时清理过期标记
        if (failedUrls.size() > 10000) {
            long now = System.currentTimeMillis();
            failedAt.entrySet().removeIf(e -> (now - e.getValue()) > FAIL_TTL_MS);
            failedUrls.removeIf(u -> !failedAt.containsKey(u));
        }
    }

    /**
     * 返回一个 1x1 透明 GIF 占位图，避免浏览器反复重试
     */
    private ResponseEntity<byte[]> buildPlaceholderResponse() {
        // 1x1 透明 GIF
        byte[] gif = new byte[]{
                (byte) 0x47, (byte) 0x49, (byte) 0x46, 0x38, 0x39, 0x61, 0x01, 0x00,
                0x01, 0x00, (byte) 0x80, 0x00, 0x00, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, 0x00, 0x00, 0x00, 0x21, (byte) 0xF9, 0x04, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00, 0x01,
                0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B
        };
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_GIF)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                .body(gif);
    }

    private String detectContentType(String url) {
        String u = url.toLowerCase();
        if (u.contains(".jpg") || u.contains(".jpeg")) return "image/jpeg";
        if (u.contains(".png")) return "image/png";
        if (u.contains(".gif")) return "image/gif";
        if (u.contains(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    /** 从 URL 提取协议+域名作为 Referer */
    private String extractReferer(String url) {
        try {
            int p = url.indexOf("://");
            if (p == -1) return "";
            int start = p + 3;
            int slash = url.indexOf('/', start);
            return slash == -1 ? url + "/" : url.substring(0, slash + 1);
        } catch (Exception e) {
            return "";
        }
    }
}
