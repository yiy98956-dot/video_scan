package com.videoplatform.infrastructure.common;

import lombok.Data;

/**
 * 统一 API 响应体 {@code ApiResult<T>}
 * <p>
 * 与 {@link R} 完全等价，提供别名方便不同风格调用。
 * 所有 Controller 返回统一走该格式：
 * <pre>{@code {"code":200,"message":"success","data":{...}}}</pre>
 *
 * @see R
 */
@Data
public class ApiResult<T> {

    private int code;
    private String message;
    private T data;

    private ApiResult() {}

    public static <T> ApiResult<T> success(T data) {
        ApiResult<T> r = new ApiResult<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> ApiResult<T> success() {
        return success(null);
    }

    public static <T> ApiResult<T> error(int code, String message) {
        ApiResult<T> r = new ApiResult<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public static <T> ApiResult<T> error(String message) {
        return error(500, message);
    }

    public static <T> ApiResult<T> unauthorized(String message) {
        return error(401, message);
    }

    public static <T> ApiResult<T> forbidden(String message) {
        return error(403, message);
    }

    public static <T> ApiResult<T> notFound(String message) {
        return error(404, message);
    }

    public static <T> ApiResult<T> badRequest(String message) {
        return error(400, message);
    }
}
