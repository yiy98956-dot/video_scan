package com.videoplatform.video.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("category_visibility")
public class CategoryVisibility {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer categoryId;
    private String categoryType; // "type" 或 "genre"
    private String categoryName;
    private Boolean visible;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
