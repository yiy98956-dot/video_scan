package com.videoplatform.history.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 播放历史列表项 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HistoryItemVO {

    private Long historyId;
    private Long videoId;           // video_meta.id
    private Integer cmsVideoId;
    private String source;
    private String title;
    private String coverUrl;
    private Integer duration;
    private Integer progress;       // 播放进度（秒），取 Redis 或 MySQL
    private Integer playCount;
    private Integer likeCount;
    private Integer collectCount;
    private Integer commentCount;
    private LocalDateTime playTime;
}
