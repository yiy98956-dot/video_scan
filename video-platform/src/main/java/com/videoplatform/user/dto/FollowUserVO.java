package com.videoplatform.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 关注列表 / 粉丝列表 VO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FollowUserVO {

    private Long userId;
    private String username;
    private String nickname;
    private String avatarUrl;
    private Boolean isFollowed;   // 我是否已关注他（仅在粉丝列表中有意义）
    private LocalDateTime followTime;
}
