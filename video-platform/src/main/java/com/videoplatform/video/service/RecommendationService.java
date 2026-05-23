package com.videoplatform.video.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.videoplatform.history.entity.PlayHistory;
import com.videoplatform.history.mapper.PlayHistoryMapper;
import com.videoplatform.video.dto.VideoItemVO;
import com.videoplatform.video.dto.VideoListVO;
import com.videoplatform.video.entity.VideoMeta;
import com.videoplatform.video.mapper.VideoMetaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 首页推荐服务 — 简易实现
 * <p>
 * 有历史：取最近 10 条播放历史，查本地 video_meta 镜获取 genre，
 * 用出现最多的 genre 调 C++ 分类接口推荐。
 * 无历史：返回 C++ 全量热门视频。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationService {

    private final PlayHistoryMapper playHistoryMapper;
    private final VideoMetaMapper videoMetaMapper;
    private final VideoMetaService videoMetaService;

    public VideoListVO recommend(Long userId, int page, int size) {
        String genre = null;

        if (userId != null) {
            List<PlayHistory> recent = playHistoryMapper.selectList(
                    new QueryWrapper<PlayHistory>()
                            .eq("user_id", userId)
                            .orderByDesc("play_time")
                            .last("LIMIT 10"));

            if (!recent.isEmpty()) {
                // 获取这些视频的 genre（从本地 video_meta 镜获取）
                List<Long> videoIds = recent.stream()
                        .map(PlayHistory::getVideoId)
                        .distinct()
                        .collect(Collectors.toList());

                List<VideoMeta> metas = videoMetaMapper.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<VideoMeta>()
                                .in(VideoMeta::getId, videoIds));

                // 统计出现最多的 genre
                Map<String, Long> genreCount = metas.stream()
                        .filter(m -> m.getTags() != null && !m.getTags().isBlank())
                        .flatMap(m -> Arrays.stream(m.getTags().split(",")))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.groupingBy(s -> s, Collectors.counting()));

                if (!genreCount.isEmpty()) {
                    genre = genreCount.entrySet().stream()
                            .max(Map.Entry.comparingByValue())
                            .get().getKey();
                }
            }
        }

        return videoMetaService.getVideoList(page, size, genre, "hot", 0, "", null);
    }
}
