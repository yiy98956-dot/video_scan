package com.videoplatform.video.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 视频列表项 VO（来自 C++ 数据 + 本地社交计数）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VideoItemVO {

    private Integer cmsVideoId;      // C++ vodId
    private String source;           // 数据源: "BaiDu", "YingHua" 等
    private Long localId;            // 本地 video_meta.id（可为 null）
    private String title;
    private String coverUrl;
    private String genre;            // C++ genre 字段: "动作,科幻"
    private String score;            // C++ score: "7.5"
    private String type;             // "电影"/"电视剧"/"综艺"
    private String remark;           // "更新至第20集"/"正片"
    private String description;      // 简介
    private String director;         // 导演
    private String actors;           // 演员
    private Integer year;            // 年份
    private String area;             // 地区
    private Integer playCount;       // 来自本地 video_meta（0 表示无本地记录）
    private Integer likeCount;
    private Integer collectCount;
    private Integer commentCount;
    private Boolean liked;
    private Boolean favorited;
}
