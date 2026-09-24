package com.github.xingjianluo.usagebillingenginejava.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** 把参数错误与状态机冲突映射为可解释的 4xx，而不是静默 500。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return build(HttpStatus.CONFLICT, "ILLEGAL_STATE", e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message) {
        log.warn("请求被拒绝 status={} code={} message={}", status.value(), code, message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
