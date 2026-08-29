package com.delivery.common.web;

/** 여러 서비스가 같이 쓰는 헤더 이름. 오타 한 번이면 멱등이 통째로 안 걸려서 상수로 둔다. */
public final class CommonHeaders {

    /** 주문 생성처럼 새 자원을 만드는 요청에 필수 (기능 정의서 3.7) */
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private CommonHeaders() {
    }
}
