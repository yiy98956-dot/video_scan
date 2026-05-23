package com.videoplatform.video.controller;

import com.videoplatform.infrastructure.common.R;
import com.videoplatform.infrastructure.security.CustomUserDetails;
import com.videoplatform.interaction.service.UserInteractionService;
import com.videoplatform.video.dto.*;
import com.videoplatform.video.service.VideoMetaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Tag(name = "视频管理", description = "视频列表 / 详情 / 分类 / 搜索 / 播放流")
@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class VideoController {

    private final VideoMetaService videoMetaService;
    private final UserInteractionService userInteractionService;

    @Value("${cpp.service.base-url:http://cpp-service:9000}")
    private String cppBaseUrl;

    @Autowired
    private CacheManager cacheManager;

    // ══════════════════════════════════════════════
    // 视频列表
    // ══════════════════════════════════════════════

    @Operation(summary = "获取视频列表",
            description = "可选 genre/type 筛选。登录后附加 isLiked / isFavorited 状态。数据来自 C++ 采集服务。")
    @GetMapping
    public R<VideoListVO> getVideoList(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "hot") String sort,
            @RequestParam(defaultValue = "0") int year,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) String type) {

        VideoListVO vo = videoMetaService.getVideoList(page, size, category, sort, year, area, type);

        // 附加用户交互状态
        Long userId = (userDetails != null) ? userDetails.getId() : null;
        if (userId != null && !vo.getItems().isEmpty()) {
            List<Integer> ids = vo.getItems().stream()
                    .map(VideoItemVO::getCmsVideoId)
                    .collect(Collectors.toList());
            Set<Long> likedIds = userInteractionService.getLikedVideoIds(userId, ids);
            Set<Long> favoritedIds = userInteractionService.getFavoritedVideoIds(userId, ids);
            for (VideoItemVO item : vo.getItems()) {
                Long vid = (long) item.getCmsVideoId();
                item.setLiked(likedIds.contains(vid));
                item.setFavorited(favoritedIds.contains(vid));
            }
        }

        return R.success(vo);
    }

    // ══════════════════════════════════════════════
    // 视频详情
    // ══════════════════════════════════════════════

    @Operation(summary = "获取视频详情",
            description = "返回完整信息（含播放源），数据来自 C++ 服务。登录后附加社交状态和播放进度。")
    @GetMapping("/{vodId}")
    public R<VideoDetailVO> getVideoDetail(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable int vodId,
            @RequestParam(required = false) String source) {

        VideoDetailVO detail = videoMetaService.getVideoDetail(vodId, source);
        if (detail == null) {
            return R.notFound("视频不存在: " + vodId);
        }

        Long userId = (userDetails != null) ? userDetails.getId() : null;
        if (userId != null) {
            // 用 cmsVideoId (vodId) 查询记忆播放进度
            detail.setProgress(userInteractionService.getPlayProgress(userId, vodId));
            // 点赞/收藏状态需要通过 localId 查询（携带 source 确保获取正确源的 localId）
            try {
                Long localId = videoMetaService.getOrCreateMeta(vodId, source);
                detail.setLiked(userInteractionService.isLiked(userId, localId));
                detail.setFavorited(userInteractionService.isFavorited(userId, localId));
            } catch (Exception e) {
                log.warn("failed to get interaction state for vodId={} source={}: {}", vodId, source, e.getMessage());
                detail.setLiked(false);
                detail.setFavorited(false);
            }
        } else {
            detail.setLiked(false);
            detail.setFavorited(false);
            detail.setProgress(0);
        }

        return R.success(detail);
    }

    // ══════════════════════════════════════════════
    // 分类列表
    // ══════════════════════════════════════════════

    @Operation(summary = "获取分类列表")
    @GetMapping("/categories")
    public R<List<String>> getCategories(@RequestParam(required = false) String type) {
        if (type != null && !type.isBlank()) {
            return R.success(videoMetaService.getGenresByType(type));
        }
        return R.success(videoMetaService.getGenres());
    }

    @Operation(summary = "获取类型列表")
    @GetMapping("/types")
    public R<List<String>> getTypes() {
        return R.success(videoMetaService.getTypes());
    }

    // ══════════════════════════════════════════════
    // 分类管理（管理员）
    // ══════════════════════════════════════════════

    @Operation(summary = "管理员获取全部分类及可见性")
    @GetMapping("/categories/admin")
    public R<Map<String, Object>> getAdminCategories() {
        return R.success(videoMetaService.getGenresForAdmin());
    }

    @Operation(summary = "管理员设置分类可见性")
    @PostMapping("/categories/visibility")
    public R<Void> setCategoryVisibility(@RequestBody Map<String, Object> body) {
        String genre = (String) body.get("name");
        if (genre == null || genre.isBlank()) return R.error("缺少 name 参数");
        boolean visible = body.get("visible") instanceof Boolean
                ? (Boolean) body.get("visible") : Boolean.parseBoolean(String.valueOf(body.get("visible")));
        videoMetaService.setGenreVisibility(genre, visible);
        return R.success();
    }

    // ══════════════════════════════════════════════
    // 流播放 — 302 重定向到 C++ proxy
    // ══════════════════════════════════════════════

    @Operation(summary = "视频流重定向",
            description = "302 重定向到 C++ 服务的 proxy 地址（防盗链/CORS 处理）")
    @GetMapping("/stream/{vodId}")
    public void stream(@PathVariable int vodId, HttpServletResponse response) {
        VideoDetailVO detail = videoMetaService.getVideoDetail(vodId, null);
        if (detail == null || detail.getPlayUrl() == null) {
            response.setStatus(404);
            return;
        }
        response.setStatus(302);
        response.setHeader("Location", detail.getPlayUrl());
    }

    @Operation(summary = "视频流播放（兼容 m3u8 后缀）", hidden = true)
    @GetMapping("/stream/{vodId}/playlist.m3u8")
    public void streamM3u8(@PathVariable int vodId, HttpServletResponse response) {
        stream(vodId, response);
    }

    // ══════════════════════════════════════════════
    // 缓存管理
    // ══════════════════════════════════════════════

    @Operation(summary = "清除单个视频详情缓存（管理员）")
    @PostMapping("/admin/cache/evict/{vodId}")
    public R<Void> evictCache(@PathVariable int vodId,
                              @RequestParam(required = false) String source) {
        String key = vodId + "_" + (source != null ? source : "null");
        Cache cache = cacheManager.getCache("video:detail");
        if (cache != null) {
            cache.evict(key);
            log.info("Cache evicted: video:detail::{}", key);
        }
        return R.success();
    }

    @Operation(summary = "清除所有视频缓存（管理员）")
    @PostMapping("/admin/cache/clear")
    public R<Void> clearAllCache() {
        for (String name : new String[]{"video:detail", "video:list", "video:categories", "video:types"}) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
                log.info("Cache cleared: {}", name);
            }
        }
        return R.success();
    }
}
