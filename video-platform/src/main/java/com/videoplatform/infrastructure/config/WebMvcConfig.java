package com.videoplatform.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置 — 头像目录静态资源映射
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${avatar.upload-dir:./avatars}")
    private String avatarUploadDir;

    /**
     * 将 /api/avatar/** 映射到本地头像目录，用于直接访问上传的头像文件。
     * 例如：/api/avatar/abc.jpg → ./avatars/abc.jpg
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/api/avatar/**")
                .addResourceLocations("file:" + avatarUploadDir + "/")
                .setCachePeriod(86400);  // 缓存 1 天
    }
}
