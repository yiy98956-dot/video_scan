package com.videoplatform.auth.controller;

import com.videoplatform.auth.dto.AuthResponse;
import com.videoplatform.auth.dto.LoginRequest;
import com.videoplatform.auth.dto.RefreshTokenRequest;
import com.videoplatform.auth.dto.RegisterRequest;
import com.videoplatform.auth.service.AuthService;
import com.videoplatform.infrastructure.common.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证管理", description = "登录 / 注册 / Token 刷新")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "用户登录", description = "使用用户名密码登录，返回 access_token 和 refresh_token")
    @PostMapping("/login")
    public R<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return R.success(response);
    }

    @Operation(summary = "用户注册", description = "注册新用户，用户名唯一，密码 BCrypt 加密")
    @PostMapping("/register")
    public R<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return R.success();
    }

    @Operation(summary = "刷新 Token", description = "使用 refresh_token 获取新的 access_token 和 refresh_token")
    @PostMapping("/refresh")
    public R<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refresh(request);
        return R.success(response);
    }
}
