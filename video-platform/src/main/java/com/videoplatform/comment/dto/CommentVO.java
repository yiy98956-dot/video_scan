package com.videoplatform.comment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评论 VO（含用户信息 + 子评论）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentVO {

    private Long id;
    private Long videoId;
    private Long userId;
    private Long parentId;
    private Long replyToUid;
    private String content;
    private Integer likeCount;
    private Integer status;
    private LocalDateTime createTime;

    // 用户信息（联表查询）
    private String nickname;
    private String avatarUrl;

    // 子评论（顶级评论最多 3 条）
    private List<CommentVO> replies;
}
