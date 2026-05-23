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
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 交互服务 — 点赞/收藏/Redis 计数
 * <p>
 * DB(video_meta) 存储基线计数，Redis 存储增量（INCR/DECR）。
 * 真实计数 = DB 基线 + Redis 增量。Redis 计数器 30 天 TTL。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionService {

    private static final String REDIS_LIKE_KEY    = "counter:like:";
    private static final String REDIS_COLLECT_KEY = "counter:collect:";
    private static final Duration COUNTER_TTL     = Duration.ofDays(30);

    private final LikeRecordMapper likeRecordMapper;
    private final FavoriteMapper favoriteMapper;
    private final VideoMetaMapper videoMetaMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    // ══════════════════════════════════════════════
    // 点赞 / 取消点赞
    // ══════════════════════════════════════════════

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> toggleLike(Long userId, Long localId) {
        LikeRecord record = likeRecordMapper.selectOne(
                new LambdaQueryWrapper<LikeRecord>()
                        .eq(LikeRecord::getUserId, userId)
                        .eq(LikeRecord::getVideoId, localId));

        boolean nowLiked;
        String redisKey = REDIS_LIKE_KEY + localId;
        if (record == null) {
            record = new LikeRecord();
            record.setUserId(userId);
            record.setVideoId(localId);
            record.setStatus(1);
            likeRecordMapper.insert(record);
            nowLiked = true;
            redisTemplate.opsForValue().increment(redisKey);
            redisTemplate.expire(redisKey, COUNTER_TTL);
        } else if (record.getStatus() == 1) {
            record.setStatus(0);
            likeRecordMapper.updateById(record);
            nowLiked = false;
            redisTemplate.opsForValue().decrement(redisKey);
        } else {
            record.setStatus(1);
            likeRecordMapper.updateById(record);
            nowLiked = true;
            redisTemplate.opsForValue().increment(redisKey);
            redisTemplate.expire(redisKey, COUNTER_TTL);
        }

        Long count = computeCount(localId, REDIS_LIKE_KEY, VideoMeta::getLikeCount);
        Map<String, Object> result = new HashMap<>();
        result.put("isLiked", nowLiked);
        result.put("likeCount", count);
        return result;
    }

    // ══════════════════════════════════════════════
    // 收藏 / 取消收藏
    // ══════════════════════════════════════════════

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> toggleFavorite(Long userId, Long localId) {
        Favorite existing = favoriteMapper.selectOne(
                new LambdaQueryWrapper<Favorite>()
                        .eq(Favorite::getUserId, userId)
                        .eq(Favorite::getVideoId, localId));

        boolean nowFavorited;
        String redisKey = REDIS_COLLECT_KEY + localId;
        if (existing == null) {
            Favorite fav = new Favorite();
            fav.setUserId(userId);
            fav.setVideoId(localId);
            favoriteMapper.insert(fav);
            nowFavorited = true;
            redisTemplate.opsForValue().increment(redisKey);
            redisTemplate.expire(redisKey, COUNTER_TTL);
        } else {
            favoriteMapper.deleteById(existing.getId());
            nowFavorited = false;
            redisTemplate.opsForValue().decrement(redisKey);
        }

        Long count = computeCount(localId, REDIS_COLLECT_KEY, VideoMeta::getCollectCount);
        Map<String, Object> result = new HashMap<>();
        result.put("isFavorited", nowFavorited);
        result.put("collectCount", count);
        return result;
    }

    // ══════════════════════════════════════════════
    // 计数工具
    // ══════════════════════════════════════════════

    private Long computeCount(Long localId, String redisPrefix,
                              Function<VideoMeta, Integer> fieldGetter) {
        VideoMeta meta = videoMetaMapper.selectById(localId);
        long dbCount = (meta != null && fieldGetter.apply(meta) != null)
                ? fieldGetter.apply(meta) : 0L;
        Object delta = redisTemplate.opsForValue().get(redisPrefix + localId);
        long deltaVal = (delta instanceof Number) ? ((Number) delta).longValue() : 0L;
        return Math.max(dbCount + deltaVal, 0);
    }
}
