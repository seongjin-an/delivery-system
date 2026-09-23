package com.delivery.notificationworker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 푸시 발송 손잡이들. 기능 정의서 NW-02 "설정으로 뺄 값" 표 그대로이고, 재시도 쪽 두 개를 더했다.
 * 시나리오 C 에서 이 값들을 바꿔가며 워커를 늘렸다 줄였다 한다.
 */
@ConfigurationProperties(prefix = "delivery.push")
public record PushProperties(

        /* 외부 푸시 게이트웨이가 허용하는 초당 요청 수. 인스턴스를 몇 대 띄우든 전체 합이 이걸 안 넘는다 */
        int globalRatePerSec,

        /* 가짜 외부 API 지연. 400 으로 올리면 큐가 쌓이는데 워커 CPU 는 논다 (시나리오 C) */
        long fakeLatencyMs,

        /* 가짜 실패 확률. 재시도와 DLQ 경로를 실제로 타보려고 둔다 */
        double failRate,

        /* 가짜 푸시를 받아줄 곳. 기본은 rider-simulator 의 /sim/push. 비워두면 POST 는 건너뛴다 */
        String webhookUrl,

        /* 한 건을 몇 번까지 보내볼지. 다 실패하면 DLQ 로 간다 */
        int maxAttempts,

        /* 실패하고 다시 보내기 전에 쉬는 시간. 두 번째부터는 두 배씩 늘린다 */
        Duration retryBackoff,

        /* 토큰이 없을 때 몇 번 더 기다려볼지 (기능 정의서 NW-02 규칙 2번, 3회) */
        int rateLimitWaits,

        /* 토큰을 다시 보기 전에 쉬는 시간 (같은 규칙, 100ms) */
        Duration rateLimitBackoff
) {
}
