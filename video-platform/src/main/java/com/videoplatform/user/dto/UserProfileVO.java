package com.videoplatform.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户个人资料（含邮箱等私密信息，不含密码）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileVO {

    private Long id;
    private String username;
    private String nickname;
    private String avatarUrl;
    private Integer status;
    private Long followerCount;
    private Long followingCount;
    private String role;
    private LocalDateTime createTime;
}
