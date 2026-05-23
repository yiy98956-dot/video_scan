package com.videoplatform.infrastructure.common;

import lombok.Data;

/**
 * 统一 API 响应体
 */
@Data
public class R<T> {

    private int code;
    private String message;
    private T data;

    private R() {}

    public static <T> R<T> success(T data) {
        R<T> r = new R<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> R<T> success() {
        return success(null);
    }

    public static <T> R<T> error(int code, String message) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public static <T> R<T> error(String message) {
        return error(500, message);
    }

    public static <T> R<T> unauthorized(String message) {
        return error(401, message);
    }

    public static <T> R<T> forbidden(String message) {
        return error(403, message);
    }

    public static <T> R<T> notFound(String message) {
        return error(404, message);
    }

    public static <T> R<T> badRequest(String message) {
        return error(400, message);
    }
}
