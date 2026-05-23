package com.videoplatform.user.controller;

import com.videoplatform.infrastructure.common.R;
import com.videoplatform.infrastructure.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;

/**
 * 头像上传控制器
 * <p>
 * - 上传: POST /api/user/avatar (multipart) → 存储到本地目录 → 返回图片 URL
 * - 访问: GET /api/avatar/{filename} (由 WebMvcConfig 静态资源映射处理)
 * <p>
 * 文件名格式: {userId}_{uuid}.{ext}，避免同名冲突也可回溯用户
 */
@Slf4j
@Tag(name = "头像上传")
@RestController
@RequestMapping("/api/user/avatar")
public class AvatarController {

    @Value("${avatar.upload-dir:./avatars}")
    private String uploadDir;

    @Value("${avatar.max-size:2097152}")
    private long maxSize;

    private Path uploadPath;

    /** 允许的图片类型 */
    private static final String[] ALLOWED_TYPES = {
        "image/jpeg", "image/png", "image/gif", "image/webp"
    };

    @PostConstruct
    public void init() {
        uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadPath);
            log.info("[Avatar] 头像目录: {}", uploadPath);
        } catch (IOException e) {
            log.error("[Avatar] 创建头像目录失败: {}", e.getMessage());
        }
    }

    @Operation(summary = "上传头像",
            description = "multipart/form-data, 支持 jpg/png/gif/webp, 最大 2MB")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Map<String, String>> uploadAvatar(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return R.badRequest("请选择文件");
        }

        // 校验文件大小
        if (file.getSize() > maxSize) {
            return R.badRequest("文件大小不能超过 2MB");
        }

        // 校验文件类型
        String contentType = file.getContentType();
        boolean allowed = false;
        for (String t : ALLOWED_TYPES) {
            if (t.equals(contentType)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            return R.badRequest("仅支持 JPG、PNG、GIF、WebP 格式");
        }

        // 提取扩展名
        String ext = "jpg";
        if (contentType != null) {
            switch (contentType) {
                case "image/png"  -> ext = "png";
                case "image/gif"  -> ext = "gif";
                case "image/webp" -> ext = "webp";
            }
        }

        // 生成唯一文件名: {userId}_{uuid}.{ext}
        String filename = userDetails.getId() + "_" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = uploadPath.resolve(filename);

        try {
            file.transferTo(target.toFile());
            log.info("[Avatar] 用户 {} 上传头像: {}", userDetails.getId(), filename);
        } catch (IOException e) {
            log.error("[Avatar] 保存失败: {}", e.getMessage());
            return R.error("头像上传失败");
        }

        // 返回可访问 URL
        String avatarUrl = "/api/avatar/" + filename;
        return R.success(Map.of("avatarUrl", avatarUrl));
    }
}
