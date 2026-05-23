package com.videoplatform.comment.service;

import com.videoplatform.comment.dto.CommentListVO;
import com.videoplatform.comment.dto.CommentRequest;
import com.videoplatform.comment.dto.CommentVO;
import com.videoplatform.comment.entity.Comment;
import com.videoplatform.comment.mapper.CommentMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 评论服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private static final String REDIS_LIKE_KEY     = "counter:comment:like:";
    private static final String REDIS_COMMENT_COUNT = "counter:video:comment:";

    private final CommentMapper commentMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    // 敏感词列表（简易 DFA）
    private final List<String> SENSITIVE_WORDS = new ArrayList<>();

    @PostConstruct
    public void init() {
        // 初始化敏感词（demo 级别，生产环境应从配置文件或数据库加载）
        SENSITIVE_WORDS.addAll(List.of("敏感词1", "敏感词2", "暴力", "色情", "赌博"));
    }

    /**
     * HTML 转义工具方法
     */
    private String escapeHtml(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;")
                .replace("/", "&#x2F;");
    }

    // ══════════════════════════════════════════════
    // 发表评论
    // ══════════════════════════════════════════════

    @Transactional(rollbackFor = Exception.class)
    public CommentVO createComment(Long videoId, Long userId, CommentRequest request) {
        // 1. XSS 防护 - HTML 转义
        String escapedContent = escapeHtml(request.getContent());
        // 2. 敏感词过滤
        String cleanContent = filterSensitive(escapedContent);

        Comment comment = new Comment();
        comment.setVideoId(videoId);
        comment.setUserId(userId);
        comment.setParentId(request.getParentId() != null ? request.getParentId() : 0L);
        comment.setReplyToUid(request.getReplyToUid() != null ? request.getReplyToUid() : 0L);
        comment.setContent(cleanContent);
        comment.setLikeCount(0);
        comment.setStatus(1);
        commentMapper.insert(comment);

        // 异步增加视频评论计数
        asyncIncrCommentCount(videoId);

        return mapToVO(Optional.ofNullable(
                commentMapper.selectCommentWithUser(comment.getId()))
                .orElse(Collections.emptyMap()));
    }

    // ══════════════════════════════════════════════
    // 查询评论列表（top + replies 内存组装）
    // ══════════════════════════════════════════════

    public CommentListVO getComments(Long videoId, int page, int size, String sort) {
        int offset = (page - 1) * size;
        List<Map<String, Object>> topRows = commentMapper.selectTopComments(videoId, offset, size);
        long total = commentMapper.countTopComments(videoId);

        if (topRows.isEmpty()) {
            CommentListVO vo = new CommentListVO();
            vo.setItems(Collections.emptyList());
            vo.setPage(page);
            vo.setSize(size);
            vo.setTotal(total);
            return vo;
        }

        // 批量查询子评论
        List<Long> parentIds = topRows.stream()
                .map(r -> Long.valueOf(r.get("id").toString()))
                .collect(Collectors.toList());
        List<Map<String, Object>> replyRows = commentMapper.selectRepliesByParentIds(parentIds);

        // 子评论按 parent_id 分组
        Map<Long, List<CommentVO>> repliesMap = new HashMap<>();
        for (Map<String, Object> rr : replyRows) {
            CommentVO reply = mapToVO(rr);
            Long pid = reply.getParentId();
            repliesMap.computeIfAbsent(pid, k -> new ArrayList<>()).add(reply);
        }

        // 组装顶级评论，每条最多 3 条子评论
        List<CommentVO> items = topRows.stream().map(row -> {
            CommentVO top = mapToVO(row);
            List<CommentVO> replies = repliesMap.getOrDefault(top.getId(), Collections.emptyList());
            // 最多 3 条
            top.setReplies(replies.size() > 3 ? replies.subList(0, 3) : replies);
            return top;
        }).collect(Collectors.toList());

        CommentListVO vo = new CommentListVO();
        vo.setItems(items);
        vo.setPage(page);
        vo.setSize(size);
        vo.setTotal(total);
        return vo;
    }

    // ══════════════════════════════════════════════
    // 评论点赞（Redis，不做用户去重）
    // ══════════════════════════════════════════════

    public Map<String, Object> likeComment(Long commentId) {
        Long count = redisTemplate.opsForValue().increment(REDIS_LIKE_KEY + commentId);
        return Map.of("commentId", commentId, "likeCount", count);
    }

    public Long getCommentLikeCount(Long commentId) {
        Object val = redisTemplate.opsForValue().get(REDIS_LIKE_KEY + commentId);
        return (val instanceof Number) ? ((Number) val).longValue() : 0L;
    }

    // ══════════════════════════════════════════════
    // 敏感词过滤
    // ══════════════════════════════════════════════

    /**
     * 简易敏感词替换 — 将敏感词替换为 ***
     */
    public String filterSensitive(String content) {
        if (content == null || content.isBlank()) return content;
        String result = content;
        for (String word : SENSITIVE_WORDS) {
            result = result.replace(word, "***");
        }
        return result;
    }

    // ══════════════════════════════════════════════
    // 内部工具
    // ══════════════════════════════════════════════

    @Async
    protected void asyncIncrCommentCount(Long videoId) {
        try {
            redisTemplate.opsForValue().increment(REDIS_COMMENT_COUNT + videoId);
        } catch (Exception e) {
            log.warn("asyncIncrCommentCount failed: {}", e.getMessage());
        }
    }

    private CommentVO mapToVO(Map<String, Object> row) {
        if (row == null || row.isEmpty()) return null;
        CommentVO vo = new CommentVO();
        vo.setId(toLong(row.get("id")));
        vo.setVideoId(toLong(row.get("video_id")));
        vo.setUserId(toLong(row.get("user_id")));
        vo.setParentId(toLong(row.get("parent_id")));
        vo.setReplyToUid(toLong(row.get("reply_to_uid")));
        vo.setContent((String) row.get("content"));
        vo.setLikeCount(toInteger(row.get("like_count")));
        vo.setStatus(toInteger(row.get("status")));
        vo.setCreateTime((LocalDateTime) row.get("create_time"));
        vo.setNickname((String) row.get("nickname"));
        vo.setAvatarUrl((String) row.get("avatar_url"));
        return vo;
    }

    private Long toLong(Object v) {
        return v != null ? ((Number) v).longValue() : null;
    }

    private Integer toInteger(Object v) {
        return v != null ? ((Number) v).intValue() : 0;
    }
}
