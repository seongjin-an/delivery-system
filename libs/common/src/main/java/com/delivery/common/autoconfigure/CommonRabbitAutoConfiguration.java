package com.delivery.common.autoconfigure;

import com.delivery.common.rabbit.RabbitTopologyConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Import;

/**
 * 래빗엠큐를 쓰는 서비스면 배차 토폴로지를 자동으로 선언한다.
 *
 * <p>선언이 멱등이라 dispatch-engine, offer-relay, notification-worker 셋이 다 선언해도 된다.
 * 오히려 그게 나은데, 어느 서비스를 먼저 띄우든 큐가 준비돼 있기 때문이다.
 */
@AutoConfiguration(after = RabbitAutoConfiguration.class)
@ConditionalOnClass(RabbitTemplate.class)
@Import(RabbitTopologyConfig.class)
public class CommonRabbitAutoConfiguration {
}
