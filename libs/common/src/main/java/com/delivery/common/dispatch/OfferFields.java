package com.delivery.common.dispatch;

/**
 * {@code dispatch:offer:{orderId}} 해시의 필드 이름.
 *
 * <p>dispatch-engine 이 쓰고 offer-relay 가 읽고 고친다. 두 서비스가 같은 보드를 보게 하는 게
 * 목적이라 이름이 어긋나면 안 된다.
 */
public final class OfferFields {

    /** 재제안할 때마다 새로 발급한다. 펜싱 규칙(기능 정의서 3.9)이 이걸로 판정한다 */
    public static final String OFFER_ID = "offerId";
    public static final String ORDER_ID = "orderId";
    public static final String RIDER_ID = "riderId";
    /** OfferState 이름 그대로 */
    public static final String STATE = "state";
    /** 몇 번째 후보인지 */
    public static final String ATTEMPT = "attempt";
    /** 제안을 보낸 시각 (epoch ms) */
    public static final String OFFERED_AT = "offeredAt";
    /**
     * 라이더가 응답한 시각 (epoch ms). respond-offer.lua 가 state 와 같이 쓴다 (DE-04, DE-05).
     *
     * <p>수락과 거절을 한 필드로 쓴다. "acceptedAt" 처럼 수락 전용으로 두면 거절까지 걸린
     * 시간을 잴 자리가 없어진다. 어느 쪽이었는지는 state 를 같이 보면 된다.
     */
    public static final String RESPONDED_AT = "respondedAt";

    /** 만료가 확정된 시각 (epoch ms). expire-offer.lua 가 쓴다 (RE-02) */
    public static final String EXPIRED_AT = "expiredAt";

    private OfferFields() {
    }
}
