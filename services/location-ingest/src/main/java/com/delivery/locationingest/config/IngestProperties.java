package com.delivery.locationingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 위치 수신 손잡이들. 시나리오 A 에서 인스턴스를 늘려가며 만질 값이라 설정으로 뺐다.
 */
@ConfigurationProperties(prefix = "delivery.ingest")
public record IngestProperties(

        /* 직전 발행 좌표에서 이만큼 안 움직였으면 발행을 건너뛴다 */
        double minMoveMeters,

        /*
         * 안 움직여도 이만큼 지나면 한 번은 발행한다.
         *
         * 기능 정의서에는 없는 값이다. 15m 필터만 두면 가게 앞에서 콜 기다리는 라이더가
         * 좌표를 계속 보내는데도 한 건도 발행이 안 되고, geo-indexer 의 오프라인 정리(GI-02)가
         * heartbeat 30초 끊긴 걸 보고 지도에서 빼버린다. 제일 배차받기 좋은 사람이 사라지는 거다.
         *
         * 10초로 둔 이유: 인스턴스가 k 대면 한 대가 같은 라이더를 3k 초에 한 번 본다.
         * 최악의 공백이 10 + 3k 초라서 4대면 22초, 6대까지는 28초로 30초 안에 든다.
         * 7대부터는 31초라 넘친다. 그 이상 늘릴 거면 이 값을 같이 줄여야 한다.
         */
        Duration maxSilence,

        /* 직전 좌표 캐시에 들고 있을 최대 라이더 수. 넘치면 오래된 것부터 버린다 */
        long cacheMaxRiders,

        /* 이만큼 좌표가 안 온 라이더는 캐시에서 버린다 (기능 정의서 LI-01 규칙 4번, 30분) */
        Duration cacheExpireAfter
) {
}
