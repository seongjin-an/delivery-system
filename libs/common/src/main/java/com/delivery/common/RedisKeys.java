package com.delivery.common;

/**
 * 레디스 키 규약.
 *
 * <p>키 이름을 문자열로 흩뿌리면 나중에 "이 키 누가 쓰는지" 를 못 찾는다. 전부 여기를 거치게 한다.
 */
public final class RedisKeys {

    /** 온라인 라이더 위치 (GEO). GEOADD / GEOSEARCH 대상 */
    public static final String RIDERS_GEO = "riders:online";

    /** 라이더 상태 해시: status, lastSeenAt, currentOrderId */
    public static String riderState(String riderId) {
        return "rider:state:" + riderId;
    }

    /**
     * 마지막 좌표 수신 시각 인덱스 (ZSET, score = epoch ms).
     *
     * <p>GEO 자료구조만으로는 "좌표가 오래된 라이더" 를 찾을 수 없어서 따로 둔다.
     * 오프라인 정리 스케줄러가 ZRANGEBYSCORE 로 대상을 뽑는다.
     */
    public static final String RIDERS_HEARTBEAT = "riders:heartbeat";

    /** 오프라인 정리 스케줄러가 인스턴스 하나만 돌게 하는 락 */
    public static final String SWEEP_OFFLINE_LOCK = "lock:sweep:offline";

    /** 배차 후보 목록 (LIST, 점수순). offer-relay 가 LPOP 으로 다음 후보를 꺼낸다 */
    public static String candidates(String orderId) {
        return "dispatch:candidates:" + orderId;
    }

    /** 진행 중인 제안 상태 해시: riderId, offeredAt, state(OFFERED/ACCEPTED/EXPIRED) */
    public static String offer(String orderId) {
        return "dispatch:offer:" + orderId;
    }

    /** 배차 락 — SET NX PX. 같은 주문을 두 인스턴스가 동시에 배차하는 걸 막는다 */
    public static String dispatchLock(String orderId) {
        return "lock:dispatch:" + orderId;
    }

    /** 라이더 점유 락 — 한 라이더에게 두 주문이 동시에 제안되는 걸 막는다 */
    public static String riderLock(String riderId) {
        return "lock:rider:" + riderId;
    }

    /** 멱등키 — 같은 요청이 두 번 와도 주문이 두 개 안 생기게 */
    public static String idempotency(String key) {
        return "idem:order:" + key;
    }

    /** 푸시 API 전역 레이트리밋 토큰버킷 (시나리오 C) */
    public static final String PUSH_RATE_BUCKET = "rate:push";

    private RedisKeys() {
    }
}
