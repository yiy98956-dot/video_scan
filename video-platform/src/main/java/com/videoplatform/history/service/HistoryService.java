package com.videoplatform.history.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoplatform.history.dto.HistoryItemVO;
import com.videoplatform.history.dto.HistoryListVO;
import com.videoplatform.history.entity.PlayHistory;
import com.videoplatform.history.mapper.PlayHistoryMapper;
import com.videoplatform.video.entity.VideoMeta;
import com.videoplatform.video.mapper.VideoMetaMapper;
import com.videoplatform.video.service.VideoMetaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 播放历史服务
 * <p>
 * 策略：
 * ① report → Redis hash 实时写入（TTL 30 天），异步写入 MySQL
 * ② list  → MySQL 联表查询（首页从 Redis 取最新进度覆盖）
 * ③ delete → MySQL 物理删除
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private static final String REDIS_HASH_PREFIX   = "user:history:";
    private static final String REDIS_ORDER_PREFIX  = "user:history:order:";
    private static final String PROGRESS_KEY_PREFIX = "video:progress:";
    private static final Duration REDIS_TTL = Duration.ofDays(30);

    private final PlayHistoryMapper playHistoryMapper;
    private final VideoMetaMapper videoMetaMapper;
    private final VideoMetaService videoMetaService;
    private final RedisTemplate<String, Object> redisTemplate;

    // ─── 上报播放进度 ───

    /**
     * 上报播放进度
     * <p>
     * videoId 支持传 cmsVideoId（vodId），内部自动解析为 video_meta.id。
     * 同步写 Redis（hash + sorted set 排序），异步写 MySQL。
     */
    public void reportProgress(Long userId, Long videoId, Integer progress, Integer duration, String source) {
        if (videoId == null || progress == null) return;

        Long localId = null;
        try {
            localId = videoMetaService.getOrCreateMeta(videoId.intValue(), source);
        } catch (Exception e) {
            log.warn("getOrCreateMeta failed for {}: {}, using raw videoId", videoId, e.getMessage());
        }

        Long effectiveId = localId != null ? localId : videoId;

        // 1) 写 Redis hash (实时)
        String hashKey = REDIS_HASH_PREFIX + userId;
        String orderKey = REDIS_ORDER_PREFIX + userId;
        redisTemplate.opsForHash().put(hashKey, String.valueOf(effectiveId), String.valueOf(progress));
        redisTemplate.opsForZSet().add(orderKey, String.valueOf(effectiveId), (double) System.currentTimeMillis());
        redisTemplate.expire(hashKey, REDIS_TTL);
        redisTemplate.expire(orderKey, REDIS_TTL);

        // 1.5) 写独立进度 key，用于 getPlayProgress 读取
        // 用原始 videoId (cmsVideoId) 做 key，和读端 getPlayProgress 匹配
        String progressKey = PROGRESS_KEY_PREFIX + userId + ":" + videoId;
        redisTemplate.opsForValue().set(progressKey, progress, REDIS_TTL);
        log.debug("reportProgress uid={} vid={} prog={} key={}", userId, videoId, progress, progressKey);

        // 2) 异步写 MySQL（使用 effectiveId）
        asyncSaveToDb(userId, effectiveId, progress, duration);
    }

    @Async
    protected void asyncSaveToDb(Long userId, Long videoId, Integer progress, Integer duration) {
        try {
            PlayHistory existing = playHistoryMapper.selectOne(
                    new LambdaQueryWrapper<PlayHistory>()
                            .eq(PlayHistory::getUserId, userId)
                            .eq(PlayHistory::getVideoId, videoId));

            if (existing != null) {
                existing.setProgress(progress);
                if (duration != null && duration > 0) {
                    existing.setDuration(duration);
                }
                existing.setPlayTime(LocalDateTime.now());
                playHistoryMapper.updateById(existing);
            } else {
                PlayHistory ph = new PlayHistory();
                ph.setUserId(userId);
                ph.setVideoId(videoId);
                ph.setProgress(progress);
                ph.setDuration(duration != null ? duration : 0);
                playHistoryMapper.insert(ph);
            }
        } catch (Exception e) {
            log.warn("asyncSaveToDb failed uid={} vid={}: {}", userId, videoId, e.getMessage());
        }
    }

    // ─── 查询播放历史 ───

    /**
     * 分页查询播放历史，按 play_time 降序，
     * 合并 Redis 中的最新进度覆盖 MySQL 值。
     */
    public HistoryListVO getHistory(Long userId, int page, int size) {
        int offset = (page - 1) * size;
        List<Map<String, Object>> rows = playHistoryMapper.selectHistoryWithVideo(userId, offset, size);
        long total = playHistoryMapper.countByUserId(userId);

        // Redis 中读取最新进度
        String hashKey = REDIS_HASH_PREFIX + userId;
        Map<Object, Object> redisProgress = redisTemplate.opsForHash().entries(hashKey);

        List<HistoryItemVO> items = rows.stream().map(row -> {
            Long localId = toLong(row.get("local_id"));
            String vidStr = String.valueOf(localId != null ? localId : row.get("video_id"));
            // 从 Redis 取最新进度（如果有）
            String redisProg = (String) redisProgress.get(vidStr);
            Integer finalProgress = redisProg != null ? Integer.parseInt(redisProg)
                    : toInteger(row.get("progress"));

            // 兼容旧数据：如果 JOIN 失败（cms_video_id 为 null），说明 ph.video_id 本身就是 cmsVideoId
            Integer cmsVideoId = toInteger(row.get("cms_video_id"));
            String title = (String) row.get("title");
            String coverUrl = (String) row.get("cover_url");
            Integer videoDuration = toInteger(row.get("video_duration"));

            String source = (String) row.get("source");

            if (cmsVideoId == null || cmsVideoId == 0) {
                Long rawVideoId = toLong(row.get("video_id"));
                if (rawVideoId != null) {
                    cmsVideoId = rawVideoId.intValue();
                    // 尝试查询或创建 video_meta 以获取完整信息
                    try {
                        Long metaLocalId = videoMetaService.getOrCreateMeta(cmsVideoId);
                        VideoMeta meta = videoMetaMapper.selectById(metaLocalId);
                        if (meta != null) {
                            title = meta.getTitle();
                            coverUrl = meta.getCoverUrl();
                            videoDuration = meta.getDuration();
                            source = meta.getSource();
                            localId = metaLocalId;
                            // 回写修正 play_history.video_id（如果 localId 和 rawVideoId 不同）
                            if (!metaLocalId.equals(rawVideoId)) {
                                try {
                                    PlayHistory ph = new PlayHistory();
                                    ph.setId(toLong(row.get("history_id")));
                                    ph.setVideoId(metaLocalId);
                                    playHistoryMapper.updateById(ph);
                                } catch (Exception ex) {
                                    log.warn("failed to fix play_history.video_id: {}", ex.getMessage());
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("fallback lookup failed for cmsVideoId={}: {}", cmsVideoId, e.getMessage());
                    }
                }
            }

            // 即使 title 为空也返回记录（显示 videoId 作为 fallback 标题）
            if (title == null || title.isEmpty()) {
                title = "视频 #" + cmsVideoId;
            }

            HistoryItemVO vo = new HistoryItemVO();
            vo.setHistoryId(toLong(row.get("history_id")));
            vo.setVideoId(toLong(row.get("video_id")));
            vo.setCmsVideoId(cmsVideoId);
            vo.setSource(source);
            vo.setTitle(title);
            vo.setCoverUrl(coverUrl);
            vo.setDuration(videoDuration);
            vo.setProgress(finalProgress);
            vo.setPlayCount(toInteger(row.get("play_count")));
            vo.setLikeCount(toInteger(row.get("like_count")));
            vo.setCollectCount(toInteger(row.get("collect_count")));
            vo.setCommentCount(toInteger(row.get("comment_count")));
            vo.setPlayTime((LocalDateTime) row.get("play_time"));
            return vo;
        }).collect(Collectors.toList());

        HistoryListVO result = new HistoryListVO();
        result.setItems(items);
        result.setPage(page);
        result.setSize(size);
        result.setTotal(total);
        return result;
    }

    // ─── 删除播放历史 ───

    @Transactional(rollbackFor = Exception.class)
    public void deleteHistory(Long id) {
        PlayHistory ph = playHistoryMapper.selectById(id);
        if (ph != null) {
            playHistoryMapper.deleteById(id);
            // 同步清除 Redis
            String hashKey = REDIS_HASH_PREFIX + ph.getUserId();
            String orderKey = REDIS_ORDER_PREFIX + ph.getUserId();
            redisTemplate.opsForHash().delete(hashKey, String.valueOf(ph.getVideoId()));
            redisTemplate.opsForZSet().remove(orderKey, String.valueOf(ph.getVideoId()));
        }
    }

    /**
     * 批量删除播放历史
     * @param userId 用户ID（权限校验用）
     * @param ids 要删除的记录ID列表
     */
    public void deleteHistoryBatch(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        // 查出这些记录，清理 Redis
        List<PlayHistory> records = playHistoryMapper.selectBatchIds(ids);
        String hashKey = REDIS_HASH_PREFIX + userId;
        String orderKey = REDIS_ORDER_PREFIX + userId;
        for (PlayHistory ph : records) {
            redisTemplate.opsForHash().delete(hashKey, String.valueOf(ph.getVideoId()));
            redisTemplate.opsForZSet().remove(orderKey, String.valueOf(ph.getVideoId()));
        }
        // 批量删除 MySQL
        playHistoryMapper.deleteBatchIds(ids);
        log.info("deleteHistoryBatch: userId={} deleted {} records", userId, ids.size());
    }

    /**
     * 获取单个视频的播放进度（秒）
     * @param userId 用户ID
     * @param videoId cmsVideoId（vodId）
     * @return 进度（秒），无记录返回 null
     */
    public Integer getPlayProgress(Long userId, Long videoId) {
        String progressKey = PROGRESS_KEY_PREFIX + userId + ":" + videoId;
        Object val = redisTemplate.opsForValue().get(progressKey);
        if (val != null) {
            return Integer.parseInt(val.toString());
        }
        return null;
    }

    // ─── 工具 ───

    private Long toLong(Object v) {
        return v != null ? ((Number) v).longValue() : null;
    }

    private Integer toInteger(Object v) {
        return v != null ? ((Number) v).intValue() : null;
    }
}
