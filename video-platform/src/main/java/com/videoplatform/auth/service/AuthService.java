package com.videoplatform.auth.service;

import com.videoplatform.auth.dto.AuthResponse;
import com.videoplatform.auth.dto.LoginRequest;
import com.videoplatform.auth.dto.RefreshTokenRequest;
import com.videoplatform.auth.dto.RegisterRequest;

public interface AuthService {

    /**
     * 登录 — 校验密码，返回令牌对
     */
    AuthResponse login(LoginRequest request);

    /**
     * 注册 — 检查唯一性，加密密码，插入用户
     */
    void register(RegisterRequest request);

    /**
     * 刷新令牌 — 校验 refresh_token，返回新令牌对
     */
    AuthResponse refresh(RefreshTokenRequest request);
}
