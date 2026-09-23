package com.delivery.common.event;

import java.time.Instant;

/**
 * order.status 토픽 페이로드. 주문 상태가 한 칸 움직였다.
 *
 * <p>여러 서비스가 같은 토픽에 보낸다. dispatch-engine 이 DISPATCHING 과 ASSIGNED 를,
 * order-api 가 PICKED_UP 과 DELIVERED 를 보낸다. status 가 문자열인 건 order-api 의
 * OrderStatus enum 이 libs/common 에 없어서다.
 *
 * @param riderId 라이더가 정해지기 전(DISPATCHING)에는 null
 */
public record OrderStatusChanged(
        long orderId,
        Long riderId,
        String status,
        Instant at
) {
}
