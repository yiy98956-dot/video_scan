package com.videoplatform.infrastructure.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final long accessExpiration;
    private final long refreshExpiration;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration}") long accessExpiration,
            @Value("${jwt.refresh-token-expiration}") long refreshExpiration) {
        byte[] keyBytes = Base64.getDecoder().decode(secret);
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessExpiration = accessExpiration;
        this.refreshExpiration = refreshExpiration;
    }

    /** 生成 Access Token */
    public String generateAccessToken(Long userId, String username, String role) {
        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("role", role != null ? role : "user")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpiration))
                .signWith(secretKey)
                .compact();
    }

    /** 生成 Refresh Token */
    public String generateRefreshToken(Long userId, String username, String role) {
        return Jwts.builder()
                .subject(username)
                .claim("userId", userId)
                .claim("role", role != null ? role : "user")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpiration))
                .signWith(secretKey)
                .compact();
    }

    /** 从 Token 解析用户名 */
    public String getUsername(String token) {
        return parseClaims(token).getPayload().getSubject();
    }

    /** 从 Token 解析用户 ID */
    public Long getUserId(String token) {
        return parseClaims(token).getPayload().get("userId", Long.class);
    }

    /** 从 Token 解析角色 */
    public String getRole(String token) {
        return parseClaims(token).getPayload().get("role", String.class);
    }

    /** 验证 Token 是否有效 */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** 获取过期时间 */
    public Date getExpiration(String token) {
        return parseClaims(token).getPayload().getExpiration();
    }

    /** 是否即将过期（少于 5 分钟） */
    public boolean isTokenExpiringSoon(String token) {
        Date exp = getExpiration(token);
        return exp != null && exp.getTime() - System.currentTimeMillis() < 300_000;
    }

    /** 获取 access_token 剩余有效期（秒） */
    public long getAccessTokenExpirationSeconds() {
        return accessExpiration / 1000;
    }

    private Jws<Claims> parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token);
    }
}
