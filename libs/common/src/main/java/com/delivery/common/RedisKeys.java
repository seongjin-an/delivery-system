package com.delivery.common;

/**
 * 레디스 키 규약.
 *
 * <p>키 이름을 문자열로 흩뿌리면 나중에 "이 키 누가 쓰는지" 를 못 찾는다. 전부 여기를 거치게 한다.
 *
 * <p>아이디를 받는 자리는 전부 long 이다. TSID 를 십진수 그대로 붙여서
 * {@code dispatch:offer:558668931353510983} 같은 모양이 된다. DB 에 보이는 값, 로그에 찍히는 값,
 * 레디스 키에 박힌 값이 전부 같은 숫자라야 장애 났을 때 눈으로 따라갈 수 있다.
 */
public final class RedisKeys {

    /** 온라인 라이더 위치 (GEO). GEOADD / GEOSEARCH 대상 */
    public static final String RIDERS_GEO = "riders:online";

    /** 라이더 상태 해시: status, lastSeenAt, currentOrderId */
    public static String riderState(long riderId) {
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
    public static String candidates(long orderId) {
        return "dispatch:candidates:" + orderId;
    }

    /** 진행 중인 제안 상태 해시: riderId, offeredAt, state(OFFERED/ACCEPTED/EXPIRED) */
    public static String offer(long orderId) {
        return "dispatch:offer:" + orderId;
    }

    /**
     * offerId → orderId 역인덱스.
     *
     * <p>제안 보드는 orderId 로 열쇠를 잡는데, 라이더가 수락할 때 들고 오는 건 offerId 하나뿐이다
     * (푸시에 실려간 게 그거고, URL 도 {@code /api/offers/{offerId}/accept} 다).
     * 그래서 offerId 로 orderId 를 되찾을 자리가 하나 필요하다.
     *
     * <p>{@code lock:rider:{riderId}} 값이 orderId 라서 그걸 대신 볼 수도 있는데, 그러면
     * 하필 제일 중요한 경우에 틀린 답이 나온다. 라이더 찜은 12초 뒤 풀리고, 그 사이 다른 주문이
     * 같은 라이더를 잡으면 값이 새 orderId 로 바뀐다. 만료된 제안을 뒤늦게 수락한 라이더에게
     * "만료됐어요" 대신 엉뚱한 주문의 판정을 돌려주게 된다.
     */
    public static String offerIndex(long offerId) {
        return "dispatch:offer:by-id:" + offerId;
    }

    /** 배차 락 — SET NX PX. 같은 주문을 두 인스턴스가 동시에 배차하는 걸 막는다 */
    public static String dispatchLock(long orderId) {
        return "lock:dispatch:" + orderId;
    }

    /** 라이더 점유 락 — 한 라이더에게 두 주문이 동시에 제안되는 걸 막는다 */
    public static String riderLock(long riderId) {
        return "lock:rider:" + riderId;
    }

    /**
     * 멱등키 — 같은 요청이 두 번 와도 주문이 두 개 안 생기게.
     *
     * <p>여기만 long 이 아니다. 이 키는 우리가 만든 아이디가 아니라 클라이언트가 헤더로 보낸 값이라
     * 형식을 우리가 정할 수 없다.
     */
    public static String idempotency(String key) {
        return "idem:order:" + key;
    }

    /** 푸시 API 전역 레이트리밋 토큰버킷 (시나리오 C) */
    public static final String PUSH_RATE_BUCKET = "rate:push";

    private RedisKeys() {
    }
}
