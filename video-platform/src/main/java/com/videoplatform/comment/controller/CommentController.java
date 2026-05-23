package com.videoplatform.comment.controller;

import com.videoplatform.comment.dto.CommentListVO;
import com.videoplatform.comment.dto.CommentRequest;
import com.videoplatform.comment.dto.CommentVO;
import com.videoplatform.comment.service.CommentService;
import com.videoplatform.infrastructure.common.R;
import com.videoplatform.infrastructure.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "评论管理", description = "获取评论 / 发表评论 / 评论点赞")
@RestController
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    // ══════════════════════════════════════════════
    // 获取评论列表
    // ══════════════════════════════════════════════

    @Operation(summary = "获取视频评论列表",
            description = "顶级评论分页 + 子评论内容组装（最多 3 条/顶级），按时间排序。")
    @GetMapping("/api/videos/{videoId}/comments")
    public R<CommentListVO> getComments(
            @PathVariable Long videoId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "time") String sort) {
        return R.success(commentService.getComments(videoId, page, size, sort));
    }

    // ══════════════════════════════════════════════
    // 发表评论
    // ══════════════════════════════════════════════

    @Operation(summary = "发表评论",
            description = "支持回复（parentId）+ 艾特（replyToUid）。自动过滤敏感词，异步增加视频评论计数。")
    @PostMapping("/api/videos/{videoId}/comments")
    public R<CommentVO> createComment(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long videoId,
            @Valid @RequestBody CommentRequest request) {
        Long userId = userDetails.getId();
        return R.success(commentService.createComment(videoId, userId, request));
    }

    // ══════════════════════════════════════════════
    // 评论点赞
    // ══════════════════════════════════════════════

    @Operation(summary = "评论点赞",
            description = "Redis 原子递增，不做用户去重。返回当前点赞数。")
    @PostMapping("/api/comments/{commentId}/like")
    public R<Map<String, Object>> likeComment(@PathVariable Long commentId) {
        return R.success(commentService.likeComment(commentId));
    }
}
