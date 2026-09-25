package com.delivery.locationingest.rabbit;

import com.delivery.common.RabbitTopology;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 발행하는 쪽에서도 큐를 선언한다. geo-indexer 가 먼저 안 떠 있으면 기본 익스체인지로 보낸
 * 메시지가 갈 데가 없어서 에러도 없이 사라진다.
 *
 * <p>인자는 geo-indexer 의 같은 이름 클래스와 똑같아야 한다. 다르면 나중에 뜨는 쪽이
 * PRECONDITION_FAILED 로 선언에 실패한다(1단계에서 만료 큐로 당했던 그거다).
 * 값을 바꿀 땐 큐를 지우고 둘 다 다시 띄운다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "delivery.ingest.transport", havingValue = "rabbit")
public class LocationQueueConfig {

    @Bean
    Queue riderLocationQueue(@Value("${delivery.location-queue.ttl-ms:0}") long ttlMs,
                             @Value("${delivery.location-queue.max-length:0}") int maxLength) {
        QueueBuilder builder = QueueBuilder.durable(RabbitTopology.Q_RIDER_LOCATION);
        if (ttlMs > 0) {
            builder.ttl((int) ttlMs);
        }
        if (maxLength > 0) {
            builder.maxLength(maxLength);
        }
        return builder.build();
    }
}
