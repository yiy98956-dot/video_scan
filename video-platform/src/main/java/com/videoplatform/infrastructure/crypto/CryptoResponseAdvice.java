package com.videoplatform.infrastructure.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Map;

/**
 * 响应加密拦截器
 * 自动加密所有 API 响应数据
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class CryptoResponseAdvice implements ResponseBodyAdvice<Object> {

    private final ObjectMapper objectMapper;

    @Value("${crypto.enabled:false}")
    private boolean cryptoEnabled;

    @Value("${crypto.secret:defaultSecretKeyForDevelopmentOnly}")
    private String cryptoSecret;

    private CryptoUtils cryptoUtils;

    @Override
    public boolean supports(MethodParameter returnType,
                           Class<? extends HttpMessageConverter<?>> converterType) {
        return cryptoEnabled;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                 MethodParameter returnType,
                                 MediaType selectedContentType,
                                 Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                 ServerHttpRequest request,
                                 ServerHttpResponse response) {

        // 只处理 JSON 响应
        if (!selectedContentType.includes(MediaType.APPLICATION_JSON)) {
            return body;
        }

        // 跳过特定路径
        String path = request.getURI().getPath();
        if (path != null && (path.startsWith("/api/movies/proxy") || path.startsWith("/api/stream"))) {
            return body;
        }

        try {
            if (cryptoUtils == null) {
                cryptoUtils = new CryptoUtils(cryptoSecret);
            }

            // 将响应转为 JSON 字符串
            String json = objectMapper.writeValueAsString(body);

            // 记录原始大小
            int originalSize = json.length();

            // 加密压缩
            String encrypted = cryptoUtils.encryptAndCompress(json);

            // 计算压缩率
            int compressedSize = encrypted.length();
            double ratio = CryptoUtils.calculateCompressionRatio(originalSize, compressedSize);

            log.debug("Response encrypted: {} → {} bytes ({}%)",
                    originalSize, compressedSize, String.format("%.1f", ratio));

            // 返回加密后的包装对象
            return Map.of(
                    "encrypted", true,
                    "data", encrypted,
                    "originalSize", originalSize,
                    "compressedSize", compressedSize
            );

        } catch (Exception e) {
            log.error("Failed to encrypt response: {}", e.getMessage());
            return body;
        }
    }
}
