package com.delivery.common.dispatch;

/**
 * 제안 상태. 기능 정의서 2.2.
 *
 * <p>수락과 만료 중에 어느 쪽이 이겼는지를 가르는 단 하나의 기준이다. 라이더의 수락은
 * dispatch-engine 이 받고 10초 뒤 만료는 offer-relay 가 받는데, 서로 다른 프로세스라
 * 각자 메모리에 들고 있으면 서로 뭘 아는지 모른다. 그래서 레디스에 둔다.
 */
public enum OfferState {

    /** 제안을 보냈고 응답을 기다린다 */
    OFFERED,
    /** 라이더가 수락했다 */
    ACCEPTED,
    /** 10초가 지났다 */
    EXPIRED,
    /** 라이더가 명시적으로 거절했다 */
    REJECTED,
    /** 후보를 다 썼다 */
    FAILED,
    /** 주문이 취소됐다 */
    CANCELLED;

    public static OfferState parseOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 이 상태면 다시 배차해도 된다 (기능 정의서 DE-01 규칙 2번의 좀비 판정 표) */
    public boolean isRetryable() {
        return this == EXPIRED || this == FAILED || this == REJECTED;
    }
}
