package com.delivery.common.response;

/** REST 응답 봉투. 프론트가 없어서 http/*.http 로 눈으로 확인할 때 형태가 일정한 게 편하다. */
public record ApiResponse<T>(boolean success, T data, String message) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, null, message);
    }
}
