package com.delivery.dispatchengine.outbox;

import com.delivery.common.dispatch.DispatchEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

/** 레디스 아웃박스 릴레이. dispatch-engine 에서 처음으로 스케줄러를 켠다 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class DispatchOutboxConfig {

    @Bean
    DispatchOutboxRelay dispatchOutboxRelay(StringRedisTemplate redis, DispatchEventPublisher publisher,
                                            @Value("${delivery.dispatch-outbox.stuck-after}") Duration stuckAfter,
                                            MeterRegistry registry) {
        return DispatchOutboxRelay.withDefaultKeys(redis, publisher, stuckAfter, Clock.systemUTC(), registry);
    }
}
