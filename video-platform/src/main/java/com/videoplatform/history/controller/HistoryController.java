package com.videoplatform.history.controller;

import com.videoplatform.history.dto.HistoryListVO;
import com.videoplatform.history.dto.ReportProgressRequest;
import com.videoplatform.history.service.HistoryService;
import com.videoplatform.infrastructure.common.R;
import com.videoplatform.infrastructure.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "播放历史", description = "上报 / 查询 / 删除播放历史和进度")
@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @Operation(summary = "上报播放进度",
            description = "同步写入 Redis（TTL 30 天），异步写入 MySQL。返回立即响应。")
    @PostMapping("/report")
    public R<Void> reportProgress(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody ReportProgressRequest request) {
        if (userDetails == null) return R.error(401, "请先登录");
        Long userId = userDetails.getId();
        historyService.reportProgress(
                userId,
                request.getVideoId(),
                request.getProgress(),
                request.getDuration(),
                request.getSource());
        return R.success();
    }

    @Operation(summary = "查询播放历史",
            description = "按 play_time 降序返回，合并 Redis 最新播放进度覆盖 MySQL 值。")
    @GetMapping
    public R<HistoryListVO> getHistory(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (userDetails == null) return R.error(401, "请先登录");
        Long userId = userDetails.getId();
        return R.success(historyService.getHistory(userId, page, size));
    }

    @Operation(summary = "获取单条播放进度",
            description = "从 Redis 或 MySQL 获取指定视频的播放进度（秒）。用于恢复播放。")
    @GetMapping("/progress/{videoId}")
    public R<Integer> getProgress(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long videoId) {
        if (userDetails == null) return R.error(401, "请先登录");
        Integer progress = historyService.getPlayProgress(userDetails.getId(), videoId);
        return R.success(progress != null ? progress : 0);
    }

    @Operation(summary = "删除播放历史",
            description = "物理删除 MySQL 记录，同时清除 Redis 中的对应进度。")
    @DeleteMapping("/{id}")
    public R<Void> deleteHistory(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long id) {
        if (userDetails == null) return R.error(401, "请先登录");
        historyService.deleteHistory(id);
        return R.success();
    }

    @Operation(summary = "批量删除播放历史",
            description = "物理删除多条 MySQL 记录，同时清除 Redis 中的对应进度。")
    @PostMapping("/batch-delete")
    public R<Void> deleteHistoryBatch(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody List<Long> ids) {
        if (userDetails == null) return R.error(401, "请先登录");
        if (ids == null || ids.isEmpty()) return R.error(400, "请选择要删除的记录");
        historyService.deleteHistoryBatch(userDetails.getId(), ids);
        return R.success();
    }
}
