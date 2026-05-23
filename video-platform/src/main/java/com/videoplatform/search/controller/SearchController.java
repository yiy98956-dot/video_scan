package com.videoplatform.search.controller;

import com.videoplatform.infrastructure.common.R;
import com.videoplatform.infrastructure.security.CustomUserDetails;
import com.videoplatform.search.service.SearchService;
import com.videoplatform.user.dto.UserVideoListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "搜索管理", description = "视频搜索（MySQL LIKE 模糊匹配）")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @Operation(summary = "搜索视频",
            description = "模糊匹配 title 和 tags，按 play_count 降序。type 参数预留（当前仅支持 video）。")
    @GetMapping
    public R<UserVideoListVO> search(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "video") String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        return R.success(searchService.search(keyword, page, size, type));
    }
}
