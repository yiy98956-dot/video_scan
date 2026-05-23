package com.videoplatform.video.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * C++ 分类/类型 JSON 映射（/api/movies/genres 或 /api/movies/types）
 * 响应格式: [{"name":"动作","count":100}, ...]
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CppCategoryDTO {
    private String name;
    private int count;
}
