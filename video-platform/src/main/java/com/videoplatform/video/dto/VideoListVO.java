package com.videoplatform.video.dto;

import com.videoplatform.video.entity.VideoMeta;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 视频列表响应（合并 CMS 数据 + 本地镜像数据）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VideoListVO {

    private List<VideoItemVO> items;
    private int page;
    private int size;
    private long total;
    private int totalPages;
}
