package com.delivery.offerrelay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 재제안 손잡이. 제안 TTL 같은 값은 dispatch-engine 과 반드시 같아야 해서
 * {@code common.dispatch.OfferProperties} 에 있고, 여기는 relay 만 쓰는 값이다.
 */
@ConfigurationProperties(prefix = "delivery.relay")
public record RelayProperties(

        /*
         * 이 횟수까지 제안했는데 아무도 안 받으면 배차 실패로 끝낸다.
         *
         * 5인 이유는 10초짜리 제안이 다섯 번이면 50초라서다. 손님이 앱을 보고 있는데
         * 1분 넘게 "배차 중" 이면 그건 배차가 아니라 방치다. 차라리 빨리 실패로 알려주고
         * 다시 시도하게 하는 게 낫다.
         */
        int maxAttempts
) {
}
