package com.delivery.common.event;

import java.time.Instant;

/** order.created 토픽 페이로드. order-api 가 아웃박스에 넣고, 그게 이 토픽으로 나간다. */
public record OrderCreated(
        String orderId,
        String storeId,
        double storeLat,
        double storeLng,
        double destLat,
        double destLng,
        String zoneId,
        int priceKrw,
        Instant createdAt
) {
}
