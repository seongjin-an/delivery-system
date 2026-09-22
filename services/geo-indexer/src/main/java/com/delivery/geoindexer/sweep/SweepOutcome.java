package com.delivery.geoindexer.sweep;

/**
 * sweep-rider.lua 가 돌려주는 값. 순서(code)를 스크립트 주석과 맞춰둔다.
 */
public enum SweepOutcome {

    /** 이미 heartbeat 에 없다. 다른 인스턴스가 먼저 치웠다 */
    GONE(0, false),
    /** 뽑은 뒤에 새 좌표가 왔다 */
    REFRESHED(1, false),
    /** 배달 중이라 안 건드렸다 */
    DELIVERING(2, true),
    /** 제안을 들고 있어서 GEO 에서만 뺐다 */
    OFFERED(3, true),
    /** 오프라인으로 만들었다 */
    OFFLINE(4, false),
    /** 모르는 status. GEO 에서만 뺐다 */
    UNKNOWN(5, true);

    private final int code;
    private final boolean staysInHeartbeat;

    SweepOutcome(int code, boolean staysInHeartbeat) {
        this.code = code;
        this.staysInHeartbeat = staysInHeartbeat;
    }

    /**
     * 처리한 뒤에도 heartbeat 의 정리 대상 구간에 남는가.
     *
     * <p>스위퍼가 다음 페이지를 어디서부터 읽을지 정할 때 쓴다. 남는 애들만큼 건너뛰어야 한다.
     */
    public boolean staysInHeartbeat() {
        return staysInHeartbeat;
    }

    public static SweepOutcome fromCode(long code) {
        for (SweepOutcome outcome : values()) {
            if (outcome.code == code) {
                return outcome;
            }
        }
        throw new IllegalArgumentException("sweep-rider.lua 가 모르는 값을 돌려줬다: " + code);
    }
}
