package com.delivery.ridersimulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시뮬레이터 기본값. POST /sim/start 본문에서 빠진 값은 여기서 채운다.
 */
@ConfigurationProperties(prefix = "delivery.simulator")
public record SimulatorProperties(

        /* 좌표를 보낼 곳. location-ingest 를 여러 개 띄우면 앞에 nginx 를 두고 그 주소를 넣는다 */
        String ingestUrl,
        /* 주문을 넣고 픽업, 완료를 부를 곳 */
        String orderUrl,
        /* 제안을 수락하고 거절할 곳 */
        String dispatchUrl,

        int riders,
        long locationIntervalMs,
        double ordersPerSec,
        double acceptRate,
        double rejectRate,
        long acceptDelayMs,

        /* 수락하고 픽업까지. 실제로는 10분쯤인데 기다릴 수 없어서 줄였다 (기능 정의서 SM-04 규칙 2번) */
        long pickupDelayMs,
        /* 픽업하고 배달 완료까지. 실제로는 20분쯤이다 */
        long completeDelayMs,

        double centerLat,
        double centerLng,
        double spreadKm,

        /* 라이더가 걷는 속도. 20km/h 면 3초에 16.7m 라 이동거리 필터(15m)를 겨우 넘는다 */
        double speedKmh,

        /* 0 이면 멈출 때까지 돈다 */
        long durationSec
) {
}
