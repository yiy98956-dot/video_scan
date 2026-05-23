package com.videoplatform.comment.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("comment")
public class Comment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long videoId;

    private Long userId;

    private Long parentId;

    private Long replyToUid;

    private String content;

    private Integer likeCount;

    @TableField(fill = FieldFill.INSERT)
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
