package com.delivery.common.event;

import java.time.Instant;

/** rider.location 토픽 페이로드. 라이더 앱이 3초마다 보내는 좌표 한 점. */
public record RiderLocation(
        long riderId,
        double lat,
        double lng,
        String zoneId,
        Instant sentAt
) {
}
