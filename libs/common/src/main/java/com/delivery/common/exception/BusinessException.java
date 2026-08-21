package com.delivery.common.exception;

/** 재시도해도 결과가 같은 실패(잘못된 요청, 이미 배차된 주문 등). 컨슈머에서 이걸 만나면 DLT 로 바로 보낸다. */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
