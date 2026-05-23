package com.videoplatform.admin.controller;

import com.videoplatform.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理员接口 — 用户管理
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final UserService userService;

    /** 分页查询所有用户 */
    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(userService.listUsers(page, size, keyword));
    }

    /** 封禁用户 */
    @PostMapping("/users/{userId}/ban")
    public ResponseEntity<Map<String, String>> banUser(@PathVariable Long userId) {
        userService.toggleBan(userId, true);
        return ResponseEntity.ok(Map.of("message", "已封禁"));
    }

    /** 解封用户 */
    @PostMapping("/users/{userId}/unban")
    public ResponseEntity<Map<String, String>> unbanUser(@PathVariable Long userId) {
        userService.toggleBan(userId, false);
        return ResponseEntity.ok(Map.of("message", "已解封"));
    }

    /** 禁言用户（durationMinutes 分钟，0=解禁） */
    @PostMapping("/users/{userId}/mute")
    public ResponseEntity<Map<String, String>> muteUser(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "60") int durationMinutes) {
        userService.muteUser(userId, durationMinutes);
        return ResponseEntity.ok(Map.of("message", durationMinutes > 0 ? "已禁言" + durationMinutes + "分钟" : "已解禁"));
    }
}
