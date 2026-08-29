package com.delivery.common.response;

import com.delivery.common.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * REST 응답 봉투. 기능 정의서 3.5.
 *
 * <p>프론트가 없어서 http/*.http 로 눈으로 확인할 때 형태가 일정한 게 편하다.
 *
 * <p>code 는 실패일 때만 붙는다(성공 응답에는 아예 안 나온다).
 * message 는 사람이 읽을 말이라 문구가 언제든 바뀔 수 있어서, 시뮬레이터가
 * "만료라서 못 받은 건지 남이 먼저 채간 건지" 를 가르려면 기계가 볼 값이 따로 필요하다.
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) String code
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.defaultMessage());
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, null, message, errorCode.name());
    }
}
