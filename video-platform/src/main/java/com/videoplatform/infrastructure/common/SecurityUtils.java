package com.videoplatform.infrastructure.common;

import com.videoplatform.infrastructure.security.CustomUserDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 获取当前登录用户的安全上下文工具类
 * <p>
 * 在非 Controller 层（Service / Mapper / Component）中需要获取 userId 时，
 * 调用此工具类而无需从 Controller 一路传参。
 */
@Slf4j
public final class SecurityUtils {

    private SecurityUtils() {}

    /**
     * 获取当前登录用户 ID
     *
     * @return userId（可能为 null 表示未登录/匿名）
     */
    public static Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails details) {
            return details.getId();
        }
        return null;
    }

    /**
     * 获取当前登录用户名
     */
    public static String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails details) {
            return details.getUsername();
        }
        return null;
    }

    /**
     * 是否已登录
     */
    public static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal());
    }
}
