package com.delivery.common;

/**
 * 래빗엠큐 익스체인지 / 큐 / 라우팅키 이름.
 *
 * <p>실제 선언(declare)은 offer-relay 의 RabbitTopologyConfig 가 한다. 여기는 이름만 모아둔 곳.
 * dispatch-engine 은 발행만 하므로 익스체인지 이름만 알면 되고, 큐가 없으면 메시지가 조용히
 * 버려지니 offer-relay 를 먼저 띄우는 게 안전하다.
 */
public final class RabbitTopology {

    // ── 배차 제안 ────────────────────────────────────────────────────────────
    /** 배차 제안 발행용 토픽 익스체인지 */
    public static final String DISPATCH_EXCHANGE = "dispatch.x";
    /** 만료된 제안이 떨어지는 데드레터 익스체인지 */
    public static final String DISPATCH_DLX = "dispatch.dlx";

    public static final String RK_OFFER_CREATED = "offer.created";
    public static final String RK_OFFER_EXPIRED = "offer.expired";

    /**
     * 컨슈머가 없는 "타이머 큐". TTL 10초가 걸려 있고, 만료되면 DLX 로 떨어진다.
     * 라이더가 제 시간에 수락하지 않았다는 사실을 알려주는 알람시계 역할.
     */
    public static final String Q_OFFER_TIMER = "dispatch.offer.timer";
    /** 만료 제안 수신 — offer-relay 가 다음 후보에게 재제안한다 */
    public static final String Q_OFFER_EXPIRED = "dispatch.offer.expired";
    /** 제안 알림 발송 — notification-worker 가 소비 */
    public static final String Q_OFFER_NOTIFY = "dispatch.offer.notify";

    // ── 알림 발송 ────────────────────────────────────────────────────────────
    public static final String NOTIFY_EXCHANGE = "notify.x";
    public static final String NOTIFY_DLX = "notify.dlx";
    public static final String RK_PUSH = "push";

    /** 푸시 발송 워크 큐. x-max-priority=10 (배차 제안 > 마케팅) */
    public static final String Q_PUSH = "notify.push";
    /** 재시도 소진분 */
    public static final String Q_PUSH_DLQ = "notify.push.dlq";

    /** 제안 유효시간(ms) — 타이머 큐의 x-message-ttl */
    public static final int OFFER_TTL_MS = 10_000;

    private RabbitTopology() {
    }
}
