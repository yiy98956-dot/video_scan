package com.videoplatform.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户公开信息（他人查看）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserPublicVO {

    private Long id;
    private String username;
    private String nickname;
    private String avatarUrl;
    private Long followerCount;   // 粉丝数
    private Long followingCount;  // 关注数
    private LocalDateTime createTime;
}
