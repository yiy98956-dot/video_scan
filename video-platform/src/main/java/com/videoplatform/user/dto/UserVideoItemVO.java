package com.videoplatform.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户喜欢/收藏列表项 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserVideoItemVO {

    private Long localId;
    private Integer cmsVideoId;
    private String source;
    private String title;
    private String coverUrl;
    private Integer duration;
    private Integer playCount;
    private Integer likeCount;
    private Integer collectCount;
    private Integer commentCount;
    private LocalDateTime actionTime;

    public static UserVideoItemVO of(Integer cmsVideoId, Long localId, String source, String title,
                                      String coverUrl, Integer likeCount, Integer collectCount,
                                      Integer commentCount) {
        UserVideoItemVO vo = new UserVideoItemVO();
        vo.cmsVideoId = cmsVideoId; vo.localId = localId; vo.source = source;
        vo.title = title; vo.coverUrl = coverUrl; vo.likeCount = likeCount;
        vo.collectCount = collectCount; vo.commentCount = commentCount;
        return vo;
    }
}
