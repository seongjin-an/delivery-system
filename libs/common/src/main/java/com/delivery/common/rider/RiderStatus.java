package com.delivery.common.rider;

/**
 * 라이더 상태. 기능 정의서 2.3.
 *
 * <pre>
 * OFFLINE ◀──▶ IDLE ──▶ OFFERED ──▶ DELIVERING ──▶ IDLE
 *                  ▲         │
 *                  └─────────┘   (거절하거나 10초가 지났을 때)
 * </pre>
 *
 * <p>후보로 뽑히는 건 IDLE 하나뿐이다. GEO 에는 좌표만 있어서 "지금 배달 중인지" 를 모르기
 * 때문에, 후보를 뽑고 나서 이 상태를 따로 확인해야 한다.
 */
public enum RiderStatus {

    /** 좌표가 30초 넘게 안 왔다 */
    OFFLINE,
    /** 온라인이고 한가하다 — 후보로 뽑힌다 */
    IDLE,
    /** 제안을 받고 아직 응답을 안 했다 */
    OFFERED,
    /** 배달 중이다 */
    DELIVERING;

    /** 모르는 값이 들어와도 죽지 않는다. 후보로 안 뽑히는 쪽으로 안전하게 떨어뜨린다 */
    public static RiderStatus parseOrOffline(String value) {
        if (value == null) {
            return OFFLINE;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return OFFLINE;
        }
    }

    public boolean isAvailable() {
        return this == IDLE;
    }
}
