package com.delivery.settlementservice.domain;

/**
 * 라이더 배달 수수료. 기능 정의서엔 fee_krw 컬럼만 있고 공식이 없어서 여기서 정했다.
 *
 * <p>기본 3,000원에 1km 를 넘으면 500m 마다 500원씩 더한다. 2.3km 면 넘은 게 1.3km 라 3칸(올림) → 4,500원.
 *
 * <p>공식을 바꿀 때는 {@link #VERSION} 도 같이 올린다. settlement_detail 에 이 값이 남아서, 리플레이(SE-03) 뒤에
 * "옛 공식으로 계산된 행이 남아 있나" 를 {@code SELECT fee_policy, COUNT(*) ... GROUP BY} 한 번으로 본다.
 * INSERT IGNORE 가 이미 있는 행을 건너뛰기 때문에, 행을 안 지우고 리셋하면 금액이 안 바뀐다.
 */
public final class FeePolicy {

    public static final String VERSION = "v1-base3000";

    static final int BASE_KRW = 3_000;
    static final int FREE_METERS = 1_000;
    static final int STEP_METERS = 500;
    static final int STEP_KRW = 500;

    public static int feeKrw(int distanceMeters) {
        int over = Math.max(0, distanceMeters - FREE_METERS);
        int steps = (over + STEP_METERS - 1) / STEP_METERS;
        return BASE_KRW + steps * STEP_KRW;
    }

    private FeePolicy() {
    }
}
