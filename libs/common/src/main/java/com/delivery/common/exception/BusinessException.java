package com.delivery.common.exception;

/**
 * 재시도해도 결과가 같은 실패(잘못된 요청, 이미 배차된 주문 등).
 *
 * <p>이 예외가 하는 일이 두 가지다.
 * 웹에서는 GlobalExceptionHandler 가 ErrorCode 를 보고 상태 코드를 정하고,
 * 컨슈머에서는 공통 에러 핸들러가 이걸 만나면 백오프 없이 바로 DLT 로 보낸다.
 * "몇 번을 다시 해도 똑같이 실패할 일"이라는 뜻을 이 타입 하나로 두 군데에 알려주는 셈이다.
 *
 * <p>반대로 레디스가 잠깐 안 붙거나 카프카가 흔들린 건 여기 해당하지 않는다.
 * 그건 그냥 RuntimeException 으로 두고 재시도를 받게 한다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
