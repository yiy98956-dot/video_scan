package com.videoplatform.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 关注操作响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FollowResponse {

    private boolean following;
    private Long followeeId;

    public static FollowResponse of(boolean following, Long followeeId) {
        FollowResponse r = new FollowResponse();
        r.following = following;
        r.followeeId = followeeId;
        return r;
    }
}
