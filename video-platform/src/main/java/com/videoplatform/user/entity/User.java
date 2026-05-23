package com.videoplatform.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("`user`")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String passwordHash;

    @TableField(fill = FieldFill.INSERT)
    private String nickname;

    private String avatarUrl;

    /** 角色: user（普通用户）, admin（管理员） */
    private String role;

    @TableField(fill = FieldFill.INSERT)
    private Integer status;

    /** 1=封禁（无法登录） */
    private Integer banned;

    /** 禁言到期时间, NULL=未禁言 */
    private LocalDateTime mutedUntil;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
