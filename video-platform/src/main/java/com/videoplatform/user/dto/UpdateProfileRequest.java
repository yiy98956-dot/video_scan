package com.videoplatform.user.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新用户信息请求 — 修改昵称/头像/用户名/密码
 */
@Data
public class UpdateProfileRequest {

    @Size(max = 64, message = "昵称最长 64 个字符")
    private String nickname;

    @Size(max = 512, message = "头像URL最长 512 个字符")
    private String avatarUrl;

    @Size(min = 2, max = 64, message = "用户名 2-64 个字符")
    private String username;           // 新用户名（可选，非空时修改）

    private String password;            // 当前密码（修改密码时必填）
    private String newPassword;         // 新密码（可选，非空时修改）
}
