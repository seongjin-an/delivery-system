package com.delivery.common.rider;

/**
 * {@code rider:state:{riderId}} 해시의 필드 이름.
 *
 * <p>geo-indexer 가 쓰고 dispatch-engine 이 읽는다. 서로 다른 서비스가 같은 해시를 다루는데
 * 필드 이름을 각자 문자열로 적으면, 한쪽이 오타를 내도 아무 에러 없이 그냥 "값이 없는" 걸로
 * 조용히 넘어간다. 그러면 모든 라이더가 OFFLINE 으로 보여서 배차가 통째로 안 된다.
 */
public final class RiderStateFields {

    /** RiderStatus 이름 그대로 */
    public static final String STATUS = "status";
    /** 마지막 좌표 수신 시각 (epoch ms) */
    public static final String LAST_SEEN_AT = "lastSeenAt";
    /** 배달 중인 주문 (없으면 빈 문자열) */
    public static final String CURRENT_ORDER_ID = "currentOrderId";

    /**
     * IDLE 이 된 시각 (epoch ms).
     *
     * <p>후보 점수의 대기 보너스가 이걸 쓴다 (기능 정의서 DE-02 규칙 4번).
     * 문서에 출처가 안 적혀 있어서 여기서 정했다 — <b>status 를 IDLE 로 바꾸는 쪽은
     * 이 값도 같이 써야 한다.</b> 안 쓰면 대기 보너스가 0이라 오래 기다린 라이더가
     * 계속 밀린다(에러는 안 난다. 그래서 더 눈에 안 띈다).
     */
    public static final String IDLE_SINCE = "idleSince";

    public static final String LAT = "lat";
    public static final String LNG = "lng";
    /** 거절 횟수. 지금은 지표로만 본다 (DE-05) */
    public static final String REJECT_COUNT = "rejectCount";

    private RiderStateFields() {
    }
}
