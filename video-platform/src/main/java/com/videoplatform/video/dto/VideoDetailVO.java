package com.videoplatform.video.dto;

import com.videoplatform.video.dto.CppMovieDTO.CppPlayGroup;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 视频详情 VO（合并 C++ 完整数据 + 本地社交计数）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VideoDetailVO {

    private Integer cmsVideoId;
    private String source;
    private Long localId;
    private String title;
    private String coverUrl;
    private int year;
    private String area;
    private String genre;
    private String type;
    private String director;
    private String actors;
    private String description;
    private String score;
    private String remark;

    private String playUrl;
    private String rawPlayUrl;
    private List<CppPlayGroup> plays;

    private Integer playCount;
    private Integer likeCount;
    private Integer collectCount;
    private Integer commentCount;
    private Integer progress;
    private Boolean liked;
    private Boolean favorited;
}
