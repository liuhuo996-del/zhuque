package com.zhuque.common;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 全局错误响应：前端统一展示“发生了什么 + 怎么修”。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException error) {
        return response(error.status(), error.what(), error.fix());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class,
            HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> validation(Exception error) {
        return response(HttpStatus.BAD_REQUEST, "请求参数不合法", rootMessage(error));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> conflict(DataIntegrityViolationException error) {
        return response(HttpStatus.CONFLICT, "数据与现有记录冲突",
                "检查 slug、名称或版本号是否重复，并刷新后重试");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception error) {
        log.error("Unhandled backend error", error);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "后端处理失败",
                "查看服务日志中的关联时间并修复后重试；未完成的发布不会被标记为成功");
    }

    private static ResponseEntity<Map<String, Object>> response(HttpStatus status, String what, String fix) {
        return ResponseEntity.status(status).body(Map.of(
                "what", what,
                "fix", fix == null ? "" : fix,
                "status", status.value(),
                "timestamp", Instant.now().toString()));
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? "检查请求体字段、类型和必填项" : message;
    }
}
