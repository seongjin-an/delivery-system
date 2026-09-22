package com.delivery.dispatchengine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 배차 손잡이들. 부하를 걸어보고 조정할 값이라 설정으로 뺐다.
 *
 * <p>여기 있는 건 <b>후보를 고르는 규칙</b>뿐이다. 락 TTL 처럼 offer-relay 와 반드시 같아야 하는
 * 값은 {@code common.dispatch.OfferProperties} 로 옮겼다. 양쪽에 따로 적어두면 한쪽만 고치는
 * 날이 온다.
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

        /*
         * OFFERED 인데 이만큼 지났으면 좀비로 본다.
         * 제안 유효시간이 10초인데 이만큼 지나도 OFFERED 라면 타이머 메시지가 애초에 없었다는 뜻이다.
         */
        Duration zombieThreshold
) {
}
