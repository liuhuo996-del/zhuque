package com.zhuque.common;

import org.springframework.http.HttpStatus;

/** 可直接转换成前端 ErrorState 所需 what/fix 结构的业务异常。 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String what;
    private final String fix;

    public ApiException(HttpStatus status, String what, String fix) {
        super(what + (fix == null || fix.isBlank() ? "" : "。" + fix));
        this.status = status;
        this.what = what;
        this.fix = fix == null ? "" : fix;
    }

    public HttpStatus status() {
        return status;
    }

    public String what() {
        return what;
    }

    public String fix() {
        return fix;
    }

    public static ApiException badRequest(String what, String fix) {
        return new ApiException(HttpStatus.BAD_REQUEST, what, fix);
    }

    public static ApiException notFound(String resource) {
        return new ApiException(HttpStatus.NOT_FOUND, resource + "不存在", "刷新页面后重试；若仍存在，请检查传入的 id");
    }

    public static ApiException conflict(String what, String fix) {
        return new ApiException(HttpStatus.CONFLICT, what, fix);
    }

    public static ApiException unavailable(String what, String fix) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, what, fix);
    }
}
