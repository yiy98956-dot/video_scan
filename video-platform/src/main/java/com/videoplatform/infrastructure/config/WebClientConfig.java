package com.videoplatform.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient 配置 — 用于异步 HTTP 请求（WebFlux）
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .codecs(config -> config
                        .defaultCodecs()
                        .maxInMemorySize(16 * 1024 * 1024)); // 16MB
    }

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        return builder.build();
    }
}
