package com.videoplatform.interaction.controller;

import com.videoplatform.infrastructure.common.R;
import com.videoplatform.infrastructure.security.CustomUserDetails;
import com.videoplatform.interaction.mapper.FavoriteMapper;
import com.videoplatform.interaction.mapper.LikeRecordMapper;
import com.videoplatform.interaction.service.InteractionService;
import com.videoplatform.user.dto.UserVideoItemVO;
import com.videoplatform.user.dto.UserVideoListVO;
import com.videoplatform.video.service.VideoMetaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "交互管理", description = "点赞 / 收藏 / 喜欢列表 / 收藏列表")
@RestController
@RequiredArgsConstructor
public class InteractionController {

    private final InteractionService interactionService;
    private final LikeRecordMapper likeRecordMapper;
    private final FavoriteMapper favoriteMapper;
    private final VideoMetaService videoMetaService;

    // ══════════════════════════════════════════════
    // 点赞 / 取消点赞
    // ══════════════════════════════════════════════

    @Operation(summary = "切换点赞状态",
            description = "未点赞 → 点赞(Redis INCR)；已点赞 → 取消(Redis DECR)。返回当前 isLiked 和 likeCount")
    @PostMapping("/api/videos/{videoId}/like")
    public R<Map<String, Object>> toggleLike(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long videoId,
            @RequestParam(required = false) String source) {
        Long userId = userDetails.getId();
        Long localId = resolveLocalId(videoId, source);
        if (localId == null) return R.notFound("视频不存在: " + videoId);
        return R.success(interactionService.toggleLike(userId, localId));
    }

    // ══════════════════════════════════════════════
    // 收藏 / 取消收藏
    // ══════════════════════════════════════════════

    @Operation(summary = "切换收藏状态",
            description = "未收藏 → 收藏(Redis INCR)；已收藏 → 取消(Redis DECR)。返回当前 isFavorited 和 collectCount")
    @PostMapping("/api/videos/{videoId}/favorite")
    public R<Map<String, Object>> toggleFavorite(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long videoId,
            @RequestParam(required = false) String source) {
        Long userId = userDetails.getId();
        Long localId = resolveLocalId(videoId, source);
        if (localId == null) return R.notFound("视频不存在: " + videoId);
        return R.success(interactionService.toggleFavorite(userId, localId));
    }

    // ══════════════════════════════════════════════
    // 我的喜欢列表
    // ══════════════════════════════════════════════

    @Operation(summary = "用户的喜欢列表")
    @GetMapping("/api/user/likes")
    public R<UserVideoListVO> getMyLikes(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = userDetails.getId();
        int offset = (page - 1) * size;

        List<Map<String, Object>> rows = likeRecordMapper.selectLikesWithVideo(userId, offset, size);
        List<UserVideoItemVO> items = rows.stream().map(this::mapLikeRow).collect(Collectors.toList());
        long total = likeRecordMapper.countActiveByUserId(userId);

        UserVideoListVO vo = new UserVideoListVO();
        vo.setItems(items);
        vo.setPage(page);
        vo.setSize(size);
        vo.setTotal(total);
        return R.success(vo);
    }

    // ══════════════════════════════════════════════
    // 我的收藏列表
    // ══════════════════════════════════════════════

    @Operation(summary = "用户的收藏列表")
    @GetMapping("/api/user/favorites")
    public R<UserVideoListVO> getMyFavorites(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = userDetails.getId();
        int offset = (page - 1) * size;

        List<Map<String, Object>> rows = favoriteMapper.selectFavoritesWithVideo(userId, offset, size);
        List<UserVideoItemVO> items = rows.stream().map(this::mapFavoriteRow).collect(Collectors.toList());
        long total = favoriteMapper.countByUserId(userId);

        UserVideoListVO vo = new UserVideoListVO();
        vo.setItems(items);
        vo.setPage(page);
        vo.setSize(size);
        vo.setTotal(total);
        return R.success(vo);
    }

    // ══════════════════════════════════════════════
    // 内部工具
    // ══════════════════════════════════════════════

    /** cmsVideoId → video_meta.id（localId），自动创建不存在的记录 */
    private Long resolveLocalId(Long cmsVideoId, String source) {
        if (cmsVideoId == null) return null;
        return videoMetaService.getOrCreateMeta(cmsVideoId.intValue(), source);
    }

    private UserVideoItemVO mapLikeRow(Map<String, Object> row) {
        UserVideoItemVO vo = new UserVideoItemVO();
        vo.setLocalId(toLong(row.get("local_id")));
        vo.setCmsVideoId(toInteger(row.get("cms_video_id")));
        vo.setSource((String) row.get("source"));
        vo.setTitle((String) row.get("title"));
        vo.setCoverUrl((String) row.get("cover_url"));
        vo.setDuration(toInteger(row.get("duration")));
        vo.setPlayCount(toInteger(row.get("play_count")));
        vo.setLikeCount(toInteger(row.get("like_count")));
        vo.setCollectCount(toInteger(row.get("collect_count")));
        vo.setCommentCount(toInteger(row.get("comment_count")));
        vo.setActionTime((LocalDateTime) row.get("like_time"));
        return vo;
    }

    private UserVideoItemVO mapFavoriteRow(Map<String, Object> row) {
        UserVideoItemVO vo = new UserVideoItemVO();
        vo.setLocalId(toLong(row.get("local_id")));
        vo.setCmsVideoId(toInteger(row.get("cms_video_id")));
        vo.setSource((String) row.get("source"));
        vo.setTitle((String) row.get("title"));
        vo.setCoverUrl((String) row.get("cover_url"));
        vo.setDuration(toInteger(row.get("duration")));
        vo.setPlayCount(toInteger(row.get("play_count")));
        vo.setLikeCount(toInteger(row.get("like_count")));
        vo.setCollectCount(toInteger(row.get("collect_count")));
        vo.setCommentCount(toInteger(row.get("comment_count")));
        vo.setActionTime((LocalDateTime) row.get("favorite_time"));
        return vo;
    }

    private Long toLong(Object v) {
        return v != null ? ((Number) v).longValue() : null;
    }

    private Integer toInteger(Object v) {
        return v != null ? ((Number) v).intValue() : null;
    }
}
