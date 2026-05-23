package com.videoplatform.history.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 播放历史上报请求
 */
@Data
public class ReportProgressRequest {

    @NotNull(message = "videoId 不能为空")
    private Long videoId;      // cmsVideoId

    @NotNull(message = "progress 不能为空")
    private Integer progress;  // 播放进度（秒）

    private Integer duration;  // 视频总时长（秒，可选）

    private String source;     // 数据源标识（可选，用于精确匹配视频信息）
}
