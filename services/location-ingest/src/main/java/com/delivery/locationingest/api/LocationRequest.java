package com.delivery.locationingest.api;

import java.time.Instant;

/**
 * LI-01 요청 본문.
 *
 * <p>lat, lng 를 빼먹고 보내면 0.0 이 들어오고, 그게 한국 범위 밖이라 400 INVALID_COORDINATE 로
 * 걸린다. 따로 @NotNull 을 안 붙인 이유다. zoneId 는 서버가 좌표로 계산한다 (기능 정의서 3.4).
 */
public record LocationRequest(
        double lat,
        double lng,
        Instant sentAt
) {
}
