package com.delivery.dispatchengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 배차 손잡이들. 부하를 걸어보고 조정할 값이라 설정으로 뺐다.
 */
@ConfigurationProperties(prefix = "delivery.dispatch")
public record DispatchProperties(

        /* 후보 검색 반경. 넓히면 후보는 늘지만 GEOSEARCH 비용도 같이 오른다 */
        int searchRadiusMeters,

        /*
         * GEOSEARCH 로 몇 명까지 훑을지. 10명만 뽑으면 배달 중인 사람이 걸러진 뒤 남는 게 없다.
         * 점심시간에는 절반 이상이 배달 중이라 30명 중 12명만 남기도 한다.
         */
        int geoCount,

        /* 최종 후보 수 */
        int maxCandidates,

        /* 대기 보너스 상한(분). 10분 넘게 기다린 라이더는 1km 정도 더 멀어도 이긴다 */
        int waitBonusCapMinutes,

        /* 배차 리스 유지 시간. 프로세스가 죽어도 이만큼 뒤엔 알아서 풀리라는 안전장치다 */
        Duration lockTtl,

        /* 라이더 찜 유지 시간. 제안 TTL 10초에 2초를 얹은 값 */
        Duration riderLockTtl,

        /* 후보 목록과 제안 보드의 TTL. 지우는 코드가 안 도는 경로가 너무 많아서 반드시 붙인다 */
        Duration stateTtl,

        /*
         * OFFERED 인데 이만큼 지났으면 좀비로 본다.
         * 제안 유효시간이 10초인데 이만큼 지나도 OFFERED 라면 타이머 메시지가 애초에 없었다는 뜻이다.
         */
        Duration zombieThreshold
) {
}
