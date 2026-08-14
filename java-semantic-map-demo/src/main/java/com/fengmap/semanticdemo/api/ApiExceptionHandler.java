package com.fengmap.semanticdemo.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(Exception exception) {
        return Map.of("code", "BAD_REQUEST", "message", safeMessage(exception, "请求参数不正确"));
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> internalError(IllegalStateException exception) {
        return Map.of("code", "MAP_LOAD_FAILED", "message", safeMessage(exception, "地图加载失败"));
    }

    private static String safeMessage(Exception exception, String fallback) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? fallback
                : exception.getMessage();
    }
}

