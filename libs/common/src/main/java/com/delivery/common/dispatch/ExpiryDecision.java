package com.delivery.common.dispatch;

/**
 * {@code expire-offer.lua} 의 반환값. RE-02 가 재제안을 해도 되는지 가른다.
 *
 * <p>진행하는 답은 {@link #RETRY} 하나고 나머지는 전부 "ack 만 하고 버려라" 다.
 * 그런데도 넷으로 갈라두는 건 <b>지표 때문이다.</b> 다 뭉쳐서 "안 했다" 로 세면,
 * 만료 큐가 이상하게 두꺼워졌을 때 원인을 못 찾는다. 펜싱에 걸린 게 많으면 라이더들이
 * 빨리빨리 거절하고 있다는 뜻이고(정상), 보드가 없는 게 많으면 재제안이 10분 넘게 늦고
 * 있다는 뜻이다(사고). 처방이 정반대다.
 */
public enum ExpiryDecision {

    /** 만료가 확정됐다. 다음 후보에게 넘긴다 */
    RETRY(1),
    /** 이미 다음 후보로 넘어간 뒤 도착한 옛날 메시지다 (기능 정의서 3.9 펜싱 규칙) */
    STALE(0),
    /** 라이더가 이미 수락했다. 10초 타이머와 수락이 부딪혔고 수락이 이겼다 */
    ACCEPTED(-1),
    /** 취소됐거나 후보를 다 쓴 주문이다 */
    CLOSED(-2),
    /** 보드가 없다. TTL 10분이 지났거나 처음 보는 주문이다 */
    GONE(-3);

    private final long code;

    ExpiryDecision(long code) {
        this.code = code;
    }

    public boolean shouldRetry() {
        return this == RETRY;
    }

    static ExpiryDecision of(Long returned) {
        if (returned == null) {
            // 재제안을 해버리면 이미 배차된 주문에 라이더가 한 명 더 붙을 수 있다.
            // 안 하는 쪽이 덜 위험하다 — 좀비 스위퍼(RE-03)나 좀비 판정이 나중에 주워간다.
            return GONE;
        }
        for (ExpiryDecision decision : values()) {
            if (decision.code == returned) {
                return decision;
            }
        }
        throw new IllegalStateException("만료 스크립트가 모르는 값을 돌려줬다: " + returned);
    }
}
