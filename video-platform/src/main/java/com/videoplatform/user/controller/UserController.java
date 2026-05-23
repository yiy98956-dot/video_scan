package com.videoplatform.user.controller;

import com.videoplatform.auth.dto.FollowResponse;
import com.videoplatform.infrastructure.common.R;
import com.videoplatform.infrastructure.security.CustomUserDetails;
import com.videoplatform.interaction.service.FollowService;
import com.videoplatform.user.dto.FollowUserVO;
import com.videoplatform.user.dto.UpdateProfileRequest;
import com.videoplatform.user.dto.UserProfileVO;
import com.videoplatform.user.dto.UserPublicVO;
import com.videoplatform.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "用户管理", description = "个人信息 / 他人信息 / 关注 / 粉丝")
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final FollowService followService;

    // ═══════════ 个人资料 ═══════════

    @Operation(summary = "获取当前登录用户信息")
    @GetMapping("/profile")
    public R<UserProfileVO> getProfile(@AuthenticationPrincipal CustomUserDetails userDetails) {
        Long userId = userDetails.getId();
        return R.success(userService.getProfile(userId));
    }

    @Operation(summary = "更新个人信息（昵称、头像）")
    @PutMapping("/profile")
    public R<UserProfileVO> updateProfile(@AuthenticationPrincipal CustomUserDetails userDetails,
                                          @Valid @RequestBody UpdateProfileRequest request) {
        Long userId = userDetails.getId();
        return R.success(userService.updateProfile(userId, request));
    }

    @Operation(summary = "查看他人公开信息")
    @GetMapping("/{id}")
    public R<UserPublicVO> getPublicInfo(@PathVariable("id") Long targetUserId) {
        return R.success(userService.getPublicInfo(targetUserId));
    }

    // ═══════════ 关注 ═══════════

    @Operation(summary = "关注/取消关注", description = "幂等：已关注则取关，未关注则关注")
    @PostMapping("/follow/{targetId}")
    public R<FollowResponse> toggleFollow(@AuthenticationPrincipal CustomUserDetails userDetails,
                                          @PathVariable Long targetId) {
        Long userId = userDetails.getId();
        return R.success(followService.toggleFollow(userId, targetId));
    }

    @Operation(summary = "当前用户关注列表")
    @GetMapping("/following")
    public R<List<FollowUserVO>> getFollowing(@AuthenticationPrincipal CustomUserDetails userDetails,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int size) {
        Long userId = userDetails.getId();
        return R.success(followService.getFollowing(userId, page, size));
    }

    @Operation(summary = "当前用户粉丝列表")
    @GetMapping("/fans")
    public R<List<FollowUserVO>> getFans(@AuthenticationPrincipal CustomUserDetails userDetails,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "10") int size) {
        Long userId = userDetails.getId();
        return R.success(followService.getFans(userId, page, size));
    }
}
