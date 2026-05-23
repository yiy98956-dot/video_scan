package com.videoplatform.infrastructure.config;

import com.videoplatform.infrastructure.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final UserDetailsService userDetailsService;

    // 公开 GET 接口（只读）
    private static final String[] PUBLIC_GET_ENDPOINTS = {
            "/api/videos",
            "/api/videos/{vodId}",
            "/api/videos/types",
            "/api/categories",
            "/api/category/tree",
            "/api/category/tree-with-counts",
            "/api/category/all",
            "/api/category/subs",
            "/api/category/stats",
            "/api/category/match",
            "/api/search",
            "/api/comments/{videoId}",  // 仅获取评论列表
            "/api/movies/proxy",
            "/api/avatar/{filename}",
            "/doc.html",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/webjars/**",
            "/favicon.ico"
    };

    // 完全公开的接口（登录相关）
    private static final String[] FULL_PUBLIC_ENDPOINTS = {
            "/api/auth/**",
            "/api/go/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // GET 请求公开访问
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_ENDPOINTS).permitAll()
                        // 完全公开的接口
                        .requestMatchers(FULL_PUBLIC_ENDPOINTS).permitAll()
                        // 评论相关写操作需要认证
                        .requestMatchers(HttpMethod.POST, "/api/comments/**").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/api/comments/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/comments/{commentId}/like").authenticated()
                        // 历史记录需要认证
                        .requestMatchers("/api/history/**").authenticated()
                        // 头像上传需要认证
                        .requestMatchers(HttpMethod.POST, "/api/avatar/upload").authenticated()
                        // 其他请求需要认证
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }
}
