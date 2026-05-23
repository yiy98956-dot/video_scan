package com.videoplatform.comment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 评论列表响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentListVO {

    private List<CommentVO> items;
    private int page;
    private int size;
    private long total;
}
