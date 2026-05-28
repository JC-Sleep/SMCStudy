package com.sc.supplychain.dto.response;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一 API 响应体
 */
@Data
public class ApiResponse<T> implements Serializable {

    private int code;
    private String message;
    private T data;

    private ApiResponse() {}

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
