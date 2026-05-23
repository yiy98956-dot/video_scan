package com.videoplatform.infrastructure.common;

import com.videoplatform.infrastructure.common.R;
import com.videoplatform.infrastructure.common.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ─── 400 — 参数校验失败 ───
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", msg);
        return R.badRequest(msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必填参数: {}", e.getParameterName());
        return R.badRequest("缺少必填参数: " + e.getParameterName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return R.badRequest(e.getMessage());
    }

    // ─── 400 — 业务异常 ───
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常 [{}]: {}", e.getCode(), e.getMessage());
        return R.error(e.getCode(), e.getMessage());
    }

    // ─── 401 — 认证失败 ───
    @ExceptionHandler(AuthenticationException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> handleAuthentication(AuthenticationException e) {
        log.warn("认证失败: {}", e.getMessage());
        return R.unauthorized("认证失败: " + e.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public R<Void> handleBadCredentials(BadCredentialsException e) {
        log.warn("认证失败: {}", e.getMessage());
        return R.unauthorized(e.getMessage());
    }

    // ─── 403 — 权限不足 ───
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public R<Void> handleAccessDenied() {
        return R.forbidden("无权访问该资源");
    }

    // ─── 500 — 服务器内部错误 ───
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleException(Exception e) {
        log.error("服务器内部错误", e);
        return R.error("服务器繁忙，请稍后重试");
    }
}
