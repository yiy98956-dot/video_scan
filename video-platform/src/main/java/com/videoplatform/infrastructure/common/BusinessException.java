package com.videoplatform.infrastructure.common;

import lombok.Getter;

/**
 * 业务异常 — 全局异常处理器捕获后返回统一格式的 JSON 响应。
 * <p>
 * 使用方式：{@code throw new BusinessException("操作失败")}
 * 或 {@code throw new BusinessException(400, "参数错误")}
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
