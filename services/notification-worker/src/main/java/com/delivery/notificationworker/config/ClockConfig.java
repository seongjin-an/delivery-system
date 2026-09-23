package com.delivery.notificationworker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 제안의 남은 시간을 잰다. 빈으로 받아두면 테스트에서 시계를 멈춰놓고 "9.5초 지났다" 를 만들 수 있다 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
