package com.videoplatform.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发表评论请求
 */
@Data
public class CommentRequest {

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 500, message = "评论内容最长 500 个字符")
    private String content;

    private Long parentId;    // 父评论 ID（0=顶级）
    private Long replyToUid;  // 回复目标用户 ID
}
