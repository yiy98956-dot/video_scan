package com.videoplatform.video.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("video_meta")
public class VideoMeta {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer cmsVideoId;

    private String source;

    private String title;

    private String coverUrl;

    private Integer duration;

    private Long categoryId;

    private String tags;

    private Integer playCount;

    private Integer likeCount;

    private Integer collectCount;

    private Integer commentCount;

    @TableField(fill = FieldFill.INSERT)
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
