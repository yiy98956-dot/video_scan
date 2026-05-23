package com.videoplatform.interaction.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoplatform.interaction.entity.Favorite;
import com.videoplatform.interaction.entity.LikeRecord;
import com.videoplatform.interaction.mapper.FavoriteMapper;
import com.videoplatform.interaction.mapper.LikeRecordMapper;
import com.videoplatform.video.entity.VideoMeta;
import com.videoplatform.video.mapper.VideoMetaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户交互状态查询 — 批量查点赞/收藏/播放进度
 * <p>
 * 避免 N+1 问题：一次 SQL 查出所有视频的交互状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserInteractionService {

    private final LikeRecordMapper likeRecordMapper;
    private final FavoriteMapper favoriteMapper;
    private final VideoMetaMapper videoMetaMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    // ─── 批量查询当前用户是否点赞 ───

    /**
     * 批量查询用户对哪些视频点了赞，返回已点赞的 cmsVideoId 集合
     * <p>
     * 先解析 cmsVideoId → localId，再查 like_record，再映射回 cmsVideoId。
     */
    public Set<Long> getLikedVideoIds(Long userId, List<Integer> cmsVideoIds) {
        if (userId == null || cmsVideoIds == null || cmsVideoIds.isEmpty()) {
            return Collections.emptySet();
        }

        // 1. cmsVideoId → localId
        List<VideoMeta> metas = videoMetaMapper.selectList(
                new LambdaQueryWrapper<VideoMeta>()
                        .in(VideoMeta::getCmsVideoId, cmsVideoIds)
                        .select(VideoMeta::getId, VideoMeta::getCmsVideoId));
        if (metas.isEmpty()) return Collections.emptySet();

        Map<Long, Integer> localToCms = metas.stream()
                .collect(Collectors.toMap(VideoMeta::getId, VideoMeta::getCmsVideoId));
        List<Long> localIds = metas.stream().map(VideoMeta::getId).collect(Collectors.toList());

        // 2. 查 like_record
        List<LikeRecord> records = likeRecordMapper.selectList(
                new LambdaQueryWrapper<LikeRecord>()
                        .eq(LikeRecord::getUserId, userId)
                        .eq(LikeRecord::getStatus, 1)
                        .in(LikeRecord::getVideoId, localIds));

        // 3. localId → cmsVideoId
        return records.stream()
                .map(r -> localToCms.get(r.getVideoId()))
                .filter(id -> id != null)
                .map(Integer::longValue)
                .collect(Collectors.toSet());
    }

    /**
     * 批量查询用户是否收藏，返回已收藏的 cmsVideoId 集合
     */
    public Set<Long> getFavoritedVideoIds(Long userId, List<Integer> cmsVideoIds) {
        if (userId == null || cmsVideoIds == null || cmsVideoIds.isEmpty()) {
            return Collections.emptySet();
        }

        // 1. cmsVideoId → localId
        List<VideoMeta> metas = videoMetaMapper.selectList(
                new LambdaQueryWrapper<VideoMeta>()
                        .in(VideoMeta::getCmsVideoId, cmsVideoIds)
                        .select(VideoMeta::getId, VideoMeta::getCmsVideoId));
        if (metas.isEmpty()) return Collections.emptySet();

        Map<Long, Integer> localToCms = metas.stream()
                .collect(Collectors.toMap(VideoMeta::getId, VideoMeta::getCmsVideoId));
        List<Long> localIds = metas.stream().map(VideoMeta::getId).collect(Collectors.toList());

        // 2. 查 favorite
        List<Favorite> records = favoriteMapper.selectList(
                new LambdaQueryWrapper<Favorite>()
                        .eq(Favorite::getUserId, userId)
                        .in(Favorite::getVideoId, localIds));

        // 3. localId → cmsVideoId
        return records.stream()
                .map(r -> localToCms.get(r.getVideoId()))
                .filter(id -> id != null)
                .map(Integer::longValue)
                .collect(Collectors.toSet());
    }

    // ─── 单视频交互状态查询 ───

    public boolean isLiked(Long userId, Long videoId) {
        if (userId == null) return false;
        Long count = likeRecordMapper.selectCount(
                new LambdaQueryWrapper<LikeRecord>()
                        .eq(LikeRecord::getUserId, userId)
                        .eq(LikeRecord::getVideoId, videoId)
                        .eq(LikeRecord::getStatus, 1));
        return count != null && count > 0;
    }

    public boolean isFavorited(Long userId, Long videoId) {
        if (userId == null) return false;
        Long count = favoriteMapper.selectCount(
                new LambdaQueryWrapper<Favorite>()
                        .eq(Favorite::getUserId, userId)
                        .eq(Favorite::getVideoId, videoId));
        return count != null && count > 0;
    }

    // ─── 播放进度 ───

    private static final String PROGRESS_KEY_PREFIX = "video:progress:";

    /**
     * 从 Redis 获取记忆播放进度（秒）
     * <p>
     * 先查独立 key，再降级查历史 hash（兼容旧数据）。
     */
    public int getPlayProgress(Long userId, Integer cmsVideoId) {
        if (userId == null || cmsVideoId == null) return 0;
        String key = PROGRESS_KEY_PREFIX + userId + ":" + cmsVideoId;
        Object val = redisTemplate.opsForValue().get(key);
        log.debug("getPlayProgress key={} val={}({})", key, val, val != null ? val.getClass().getSimpleName() : "null");
        if (val instanceof Number) {
            return ((Number) val).intValue();
        }
        if (val instanceof String) {
            try {
                return Integer.parseInt((String) val);
            } catch (NumberFormatException e) {
                log.warn("parseInt failed for key={} val={}", key, val);
            }
        }

        // 降级：从历史 hash 中查（兼容旧数据）
        try {
            String hashKey = "user:history:" + userId;
            Object hashVal = redisTemplate.opsForHash().get(hashKey, String.valueOf(cmsVideoId));
            log.debug("getPlayProgress fallback hashKey={} field={} val={}", hashKey, cmsVideoId, hashVal);
            if (hashVal instanceof String) {
                return Integer.parseInt((String) hashVal);
            }
        } catch (Exception e) {
            log.warn("getPlayProgress fallback failed: {}", e.getMessage());
        }

        return 0;
    }

    /**
     * 保存播放进度到 Redis（由播放接口异步调用）
     */
    public void savePlayProgress(Long userId, Integer cmsVideoId, int progress) {
        if (userId == null || cmsVideoId == null) return;
        String key = PROGRESS_KEY_PREFIX + userId + ":" + cmsVideoId;
        redisTemplate.opsForValue().set(key, progress);
    }
}
