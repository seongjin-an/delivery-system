package com.delivery.geoindexer.rabbit;

import com.delivery.common.RabbitTopology;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 2단계 실험: rider.location 을 래빗엠큐 큐로 받는다.
 *
 * <p>카프카 쪽과 최대한 맞췄다. 컨슈머 3개(카프카 concurrency 3), 한 번에 최대 500건
 * (max-poll-records 500), 실패해도 다시 안 넣는다(카프카도 그냥 ack 한다).
 *
 * <p>배치를 모으는 시간은 50ms 로 끊는다. 이걸 안 주면 500건이 찰 때까지 기다린다.
 * 초당 3000건을 컨슈머 셋이 나눠 받으면 한 명당 초당 1000건이라 500건 채우는 데 0.5초가
 * 그냥 얹힌다. 카프카 poll 은 가져온 만큼 바로 돌려주니까 그 차이를 없앤 것이다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "delivery.listener.rider-location.transport", havingValue = "rabbit")
public class RabbitLocationConfig {

    /** location-ingest 의 LocationQueueConfig 와 인자가 같아야 한다 */
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

    @Bean
    SimpleRabbitListenerContainerFactory locationBatchFactory(
            ConnectionFactory connectionFactory,
            @Value("${delivery.listener.rider-location.concurrency}") int concurrency) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(concurrency);
        factory.setPrefetchCount(500);
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(500);
        factory.setBatchReceiveTimeout(50L);
        // 리스너가 예외 없이 끝나면 배치를 통째로 ack 한다. handle() 은 예외를 안 던진다.
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
