package com.delivery.common.kafka;

import com.delivery.common.KafkaTopics;
import com.delivery.common.exception.BusinessException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * 컨슈머 공통 에러 처리. 기능 정의서 3.8.
 *
 * <p>규칙은 두 줄이다.
 * BusinessException 이면 재시도 없이 바로 DLT 로 보내고, 그 외 예외는 500ms 부터 두 배씩
 * 늘려가며 3회까지 다시 해본다. 그래도 안 되면 DLT 로 간다.
 *
 * <p>왜 이렇게 갈랐냐면, 다시 해서 결과가 달라질 실패와 안 달라질 실패는 처방이 정반대여서다.
 * 레디스가 잠깐 안 붙은 거면 1초 뒤에 다시 하면 대개 된다. 반면 "이미 다른 라이더가 수락한 주문"
 * 같은 건 백 번을 다시 해도 똑같이 실패한다. 그런 걸 재시도하면 컨슈머가 그 파티션에 앉아서
 * 같은 레코드를 붙잡고 있는 동안 뒤에 있는 멀쩡한 레코드가 전부 밀린다.
 */
public final class ConsumerErrorHandlerFactory {

    private static final Logger log = LoggerFactory.getLogger(ConsumerErrorHandlerFactory.class);

    private static final long INITIAL_INTERVAL_MS = 500L;
    private static final double MULTIPLIER = 2.0;
    private static final int MAX_RETRIES = 3;

    public static DefaultErrorHandler create(KafkaOperations<?, ?> template) {
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer(template), backOff());

        // 이 타입은 재시도 대상에서 뺀다 → 첫 실패에 곧바로 recoverer(=DLT 발행)로 넘어간다.
        handler.addNotRetryableExceptions(BusinessException.class);

        handler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("컨슈머 재시도 {}회차: topic={} partition={} offset={} 원인={}",
                        deliveryAttempt, record.topic(), record.partition(), record.offset(),
                        ex.getMessage()));

        return handler;
    }

    /**
     * DLT 목적지를 정한다. 이름은 스프링 카프카 규약대로 원본토픽 + ".DLT".
     *
     * <p>파티션을 -1 로 넘기는 게 핵심이다. 기본 동작은 원본과 같은 파티션 번호로 보내는 건데,
     * 우리 DLT 토픽은 파티션이 3개고 원본(rider.location, order.created)은 6개다.
     * 그대로 두면 4~6번 파티션에서 실패한 레코드가 DLT 에 없는 파티션을 찾다가 발행 자체가 실패한다.
     * -1 이면 카프카가 알아서 고른다.
     */
    private static DeadLetterPublishingRecoverer recoverer(KafkaOperations<?, ?> template) {
        return new DeadLetterPublishingRecoverer(template,
                (record, ex) -> {
                    String dlt = record.topic() + KafkaTopics.SUFFIX_DLT;
                    log.error("DLT 로 보낸다: {} → {} offset={} 원인={}",
                            record.topic(), dlt, record.offset(), ex.toString());
                    return new TopicPartition(dlt, -1);
                });
    }

    private static ExponentialBackOff backOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_INTERVAL_MS, MULTIPLIER);
        // setMaxAttempts 는 "재시도 횟수" 다. 최초 시도까지 합치면 한 레코드를 최대 네 번 처리한다.
        backOff.setMaxAttempts(MAX_RETRIES);
        return backOff;  // 500ms → 1s → 2s
    }

    private ConsumerErrorHandlerFactory() {
    }
}
