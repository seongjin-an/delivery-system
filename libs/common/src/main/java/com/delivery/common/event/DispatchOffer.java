package com.delivery.common.event;

import java.time.Instant;

/**
 * 래빗엠큐로 흐르는 배차 제안.
 *
 * <p>attempt 는 몇 번째 후보인지. 이게 계속 올라가면 "이 지역에 라이더가 없다"는 신호라
 * 나중에 대시보드 지표로도 쓴다.
 */
public record DispatchOffer(
        long offerId,
        long orderId,
        long riderId,
        int attempt,
        Instant offeredAt
) {
}
