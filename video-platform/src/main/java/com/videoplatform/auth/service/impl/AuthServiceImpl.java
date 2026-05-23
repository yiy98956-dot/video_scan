package com.videoplatform.auth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.videoplatform.auth.dto.AuthResponse;
import com.videoplatform.auth.dto.LoginRequest;
import com.videoplatform.auth.dto.RefreshTokenRequest;
import com.videoplatform.auth.dto.RegisterRequest;
import com.videoplatform.auth.service.AuthService;
import com.videoplatform.infrastructure.config.JwtUtil;
import com.videoplatform.user.entity.User;
import com.videoplatform.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    @Override
    public AuthResponse login(LoginRequest request) {
        // 查询用户
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, request.getUsername()));

        if (user == null) {
            throw new BadCredentialsException("用户名或密码错误");
        }

        // 校验密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("用户名或密码错误");
        }

        // 生成令牌
        return buildAuthResponse(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterRequest request) {
        // 检查用户名唯一性
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, request.getUsername()));

        if (count != null && count > 0) {
            throw new IllegalArgumentException("用户名已存在");
        }

        // 构建用户对象
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        // 昵称：有则用，无则用用户名
        user.setNickname(request.getNickname() != null && !request.getNickname().isBlank()
                ? request.getNickname() : request.getUsername());

        userMapper.insert(user);
    }

    @Override
    public AuthResponse refresh(RefreshTokenRequest request) {
        String token = request.getRefreshToken();

        // 校验 refresh_token 有效性
        if (!jwtUtil.validateToken(token)) {
            throw new BadCredentialsException("refresh_token 无效或已过期");
        }

        Long userId = jwtUtil.getUserId(token);
        String username = jwtUtil.getUsername(token);

        // 重新查询用户（确保用户仍然存在且未禁用）
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BadCredentialsException("用户不存在");
        }

        // 生成新令牌对
        return buildAuthResponse(user);
    }

    /**
     * 根据用户构造完整的 AuthResponse
     */
    private AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtUtil.generateAccessToken(user.getId(), user.getUsername(), user.getRole());
        String refreshToken = jwtUtil.generateRefreshToken(user.getId(), user.getUsername(), user.getRole());
        long expiresIn = jwtUtil.getAccessTokenExpirationSeconds();

        AuthResponse response = new AuthResponse();
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);
        response.setTokenType("Bearer");
        response.setExpiresIn(expiresIn);
        return response;
    }
}
