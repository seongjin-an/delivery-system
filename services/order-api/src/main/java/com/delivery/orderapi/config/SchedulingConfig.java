package com.delivery.orderapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 아웃박스 폴러(OR-06)를 돌리려면 이게 있어야 한다. 없으면 @Scheduled 가 조용히 무시된다 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
