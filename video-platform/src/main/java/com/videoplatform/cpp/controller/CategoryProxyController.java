package com.videoplatform.cpp.controller;

import com.videoplatform.infrastructure.common.R;
import com.videoplatform.infrastructure.security.CustomUserDetails;
import com.videoplatform.video.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 分类系统控制器
 * <p>
 * Java 接管分类管理，使用 DB 持久化可见性设置。
 * 一级分类（9个标准）+ 二级子分类 + 从 Go 获取数量统计。
 */
@Slf4j
@Tag(name = "分类管理", description = "分类树 / 可见性控制（Java 接管）")
@RestController
@RequestMapping("/api/category")
@RequiredArgsConstructor
public class CategoryProxyController {

    private final CategoryService categoryService;
    private final com.videoplatform.video.client.CppVideoClient cppClient;

    @Operation(summary = "获取可见分类树", description = "前端导航用，自动过滤隐藏分类")
    @GetMapping("/tree")
    public R<Object> tree() {
        return jsonResult(cppClient.fetchRaw("/api/category/tree"));
    }

    @Operation(summary = "获取带影片数的分类树",
            description = "Java 接管：从 Go 获取数据 → 重分类 → 应用 DB 可见性规则。?showHidden=1 管理后台用")
    @GetMapping("/tree-with-counts")
    public R<Map<String, Object>> treeWithCounts(
            @RequestParam(defaultValue = "false") boolean showHidden) {
        return R.success(categoryService.getCorrectedTree(showHidden));
    }

    @Operation(summary = "触发纠正计数重算",
            description = "后台从 Go 拉全量数据 → refineType(Java层) → 正确计数")
    @PostMapping("/recount")
    public R<String> recount() {
        categoryService.triggerRecount();
        return R.success("已触发纠正计数重算，后台进行中...");
    }

    @Operation(summary = "获取分类分布统计", description = "诊断用，查看各类型影片数量")
    @GetMapping("/stats")
    public R<Object> stats() {
        return jsonResult(cppClient.fetchRaw("/api/category/stats"));
    }

    @Operation(summary = "获取全部分类", description = "管理后台用，含隐藏分类")
    @GetMapping("/all")
    public R<Object> all() {
        return jsonResult(cppClient.fetchRaw("/api/category/all"));
    }

    @Operation(summary = "获取一级下的二级分类")
    @GetMapping("/subs")
    public R<Object> subs(@RequestParam int pid) {
        return jsonResult(cppClient.fetchRaw("/api/category/subs?pid=" + pid));
    }

    @Operation(summary = "切换分类显示/隐藏",
            description = "GET请求，?id=分类ID。Java 接管，持久化到 MySQL。仅管理员可操作。")
    @GetMapping("/toggle")
    public R<Map<String, Object>> toggle(@RequestParam int id, Authentication auth) {
        // 权限检查：仅 admin 可用
        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails)) {
            return R.error("需要登录");
        }
        CustomUserDetails user = (CustomUserDetails) auth.getPrincipal();
        if (!"admin".equals(user.getRole())) {
            return R.error("仅管理员可操作");
        }
        return R.success(categoryService.toggle(id));
    }

    @Operation(summary = "更新分类", description = "POST，JSON body含id/name/alias/sort/is_show")
    @PostMapping("/update")
    public R<Object> update(@RequestBody String body) {
        String result = cppClient.postRaw("/api/category/update", body);
        return jsonResult(result);
    }

    @Operation(summary = "新增二级子分类", description = "POST，JSON body含pid/name/alias")
    @PostMapping("/add")
    public R<Object> add(@RequestBody String body) {
        String result = cppClient.postRaw("/api/category/add", body);
        return jsonResult(result);
    }

    @Operation(summary = "删除二级子分类", description = "GET请求，?id=分类ID")
    @GetMapping("/delete")
    public R<Object> delete(@RequestParam int id) {
        return jsonResult(cppClient.fetchRaw("/api/category/delete?id=" + id));
    }

    @Operation(summary = "采集分类匹配测试", description = "传入title/genre/type，返回匹配的一二级分类")
    @GetMapping("/match")
    public R<Object> match(
            @RequestParam(defaultValue = "") String title,
            @RequestParam(defaultValue = "") String genre,
            @RequestParam(defaultValue = "") String type) {
        String path = "/api/category/match?title=" + URLEncoder.encode(title, StandardCharsets.UTF_8)
                + "&genre=" + URLEncoder.encode(genre, StandardCharsets.UTF_8)
                + "&type=" + URLEncoder.encode(type, StandardCharsets.UTF_8);
        return jsonResult(cppClient.fetchRaw(path));
    }

    private R<Object> jsonResult(String raw) {
        if (raw == null) return R.error("Go 服务连接失败");
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return R.success(mapper.readTree(raw));
        } catch (Exception e) {
            return R.success(raw);
        }
    }
}
