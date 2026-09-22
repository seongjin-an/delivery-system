package com.delivery.locationingest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 이동거리 필터의 "10초 조용했나" 랑 sentAt 미래 판정이 시각을 쓴다.
 * 빈으로 받아두면 테스트에서 시계를 멈춰놓고 돌릴 수 있다. 안 그러면 10초짜리 테스트가 진짜 10초 걸린다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
