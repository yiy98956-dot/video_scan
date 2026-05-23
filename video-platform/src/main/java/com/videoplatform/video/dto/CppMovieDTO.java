package com.videoplatform.video.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * C++ 服务返回的原始电影 JSON 映射
 * <p>
 * C++ `/api/movies/page` 和 `/api/movies/detail` 返回的 JSON 结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CppMovieDTO {

    private int id;                     // cmsVideoId (vodId)
    private String title;
    private String coverUrl;
    private int year;
    private String area;
    private String genre;               // 逗号分隔: "动作,科幻"
    private String type;                // "电影" 或 "电视剧"
    private String rawType;             // CMS源原始type_name (如"国产动漫""连续剧")
    private String director;
    private String actors;
    private String description;
    private String score;
    private String remark;              // "更新至12集"
    private String source;              // 数据源: "bdzy"
    private String listDate;
    private String status;
    private String lastCheckTime;
    private boolean hasUpdate;
    private List<CppPlayGroup> plays;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CppPlayGroup {
        private String from;
        private String name;            // "线路1"
        private List<CppPlayUrl> urls;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CppPlayUrl {
        private String episode;
        private String url;
    }
}
