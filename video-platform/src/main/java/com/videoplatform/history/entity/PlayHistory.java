package com.videoplatform.history.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("play_history")
public class PlayHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long videoId;

    private Integer progress;

    private Integer duration;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime playTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
