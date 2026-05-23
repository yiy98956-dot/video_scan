package com.videoplatform.search.service;

import com.videoplatform.user.dto.UserVideoItemVO;
import com.videoplatform.user.dto.UserVideoListVO;
import com.videoplatform.video.client.CppVideoClient;
import com.videoplatform.video.dto.CppMovieDTO;
import com.videoplatform.video.dto.VideoItemVO;
import com.videoplatform.video.service.VideoMetaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 搜索服务 — 通过 C++ 内置倒排索引搜索
 * <p>
 * 调用 C++ /api/movies/search?q=keyword 获取结果，
 * 比 MySQL LIKE 更高效、更准确。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final VideoMetaService videoMetaService;

    /**
     * 搜索视频
     */
    public UserVideoListVO search(String keyword, int page, int size, String type) {
        if (keyword == null || keyword.isBlank()) {
            UserVideoListVO vo = new UserVideoListVO();
            vo.setItems(Collections.emptyList());
            vo.setPage(page);
            vo.setSize(size);
            vo.setTotal(0);
            return vo;
        }

        List<VideoItemVO> items = videoMetaService.search(keyword.trim());

        // C++ 返回全量结果，Java 端做分页截取
        int total = items.size();
        int fromIndex = (page - 1) * size;
        int toIndex = Math.min(fromIndex + size, total);

        List<UserVideoItemVO> pageItems;
        if (fromIndex >= total) {
            pageItems = Collections.emptyList();
        } else {
            pageItems = items.subList(fromIndex, toIndex).stream()
                    .map(this::toUserItem)
                    .collect(Collectors.toList());
        }

        UserVideoListVO result = new UserVideoListVO();
        result.setItems(pageItems);
        result.setPage(page);
        result.setSize(size);
        result.setTotal(total);
        return result;
    }

    private UserVideoItemVO toUserItem(VideoItemVO v) {
        UserVideoItemVO vo = new UserVideoItemVO();
        vo.setCmsVideoId(v.getCmsVideoId());
        vo.setLocalId(v.getLocalId());
        vo.setSource(v.getSource());
        vo.setTitle(v.getTitle());
        vo.setCoverUrl(v.getCoverUrl());
        vo.setLikeCount(v.getLikeCount());
        vo.setCollectCount(v.getCollectCount());
        vo.setCommentCount(v.getCommentCount());
        return vo;
    }
}
