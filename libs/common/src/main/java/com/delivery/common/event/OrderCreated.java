package com.delivery.common.event;

import java.time.Instant;

/**
 * order.created 토픽 페이로드. order-api 가 아웃박스에 넣고, 그게 이 토픽으로 나간다.
 *
 * <p>orderId 가 long 이라 JSON 에는 숫자로 나간다. 여기 붙는 소비자가 전부 자바라 문제가 없는데,
 * 나중에 브라우저 프론트가 붙으면 그때는 문자열로 바꿔서 내보내야 한다.
 * 자바스크립트의 숫자는 안전한 정수 범위가 2^53 까지라 TSID 를 그대로 받으면 뒷자리가 뭉개진다.
 */
public record OrderCreated(
        long orderId,
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
