package com.delivery.notificationworker.push;

/** 외부 푸시 API 가 한 번 실패했다. 재시도할 수 있는 실패다 */
public class PushFailedException extends RuntimeException {

    public PushFailedException(String message) {
        super(message);
    }

    public PushFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
