package com.delivery.geoindexer.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 오프라인 정리(GI-02)를 돌리려면 이게 있어야 한다. 없으면 @Scheduled 가 에러 없이 조용히 무시된다.
 *
 * <p>delivery.sweep.enabled=false 로 끌 수 있게 해뒀다. 시나리오 B 에서 컨슈머 처리량만 재고 싶을 때
 * 10초마다 끼어드는 파이프라인이 재는 값을 흐트러뜨려서다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "delivery.sweep.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
