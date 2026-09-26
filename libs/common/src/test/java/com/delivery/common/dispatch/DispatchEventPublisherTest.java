package com.delivery.common.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import com.delivery.common.KafkaTopics;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 발행 실패가 호출한 쪽까지 올라오는지 본다.
 *
 * <p>예전엔 send() 의 future 를 버려서, 브로커가 거절해도 여기서는 아무 일도 없었다.
 * 그러면 컨슈머가 ack 를 해버려서 재시도가 안 일어나고, 배차 결과가 조용히 사라진다.
 */
class DispatchEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final DispatchEventPublisher publisher = new DispatchEventPublisher(kafkaTemplate);

    @Test
    void brokerRejectionIsThrownToCaller() {
        given(kafkaTemplate.send(eq(KafkaTopics.DISPATCH_FAILED), anyString(), anyString()))
                .willReturn(CompletableFuture.failedFuture(new TimeoutException("브로커 응답 없음")));

        assertThatThrownBy(() -> publisher.publishFailed(1L, 5, "MAX_ATTEMPTS"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(KafkaTopics.DISPATCH_FAILED);
    }

    @Test
    void acknowledgedSendReturnsNormally() {
        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThatCode(() -> publisher.publishDispatching(1L)).doesNotThrowAnyException();
    }

    /** 아웃박스에서 꺼낸 걸 보낼 때도 브로커가 거절하면 예외가 올라가야 한다. 릴레이가 그걸 보고 inflight 에 남긴다 */
    @Test
    void outboxSendThrowsWhenBrokerRejects() {
        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willReturn(CompletableFuture.failedFuture(new TimeoutException("브로커 응답 없음")));
        OutboxEnvelope envelope = OutboxEnvelope.fromJson(DispatchEventPublisher.assignedEvents(1L, 2L, 3L, 1).get(0));

        assertThatThrownBy(() -> publisher.send(envelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(KafkaTopics.DISPATCH_ASSIGNED);
    }

    /** 배차 확정 두 건은 같은 키(orderId)로 순서대로 만든다. 한 파티션에 dispatch.assigned 가 먼저 간다 */
    @Test
    void assignedEventsAreKeyedByOrder() {
        List<OutboxEnvelope> envelopes = DispatchEventPublisher.assignedEvents(7L, 8L, 9L, 3).stream()
                .map(OutboxEnvelope::fromJson).toList();

        assertThat(envelopes).extracting(OutboxEnvelope::topic)
                .containsExactly(KafkaTopics.DISPATCH_ASSIGNED, KafkaTopics.ORDER_STATUS);
        assertThat(envelopes).extracting(OutboxEnvelope::key).containsOnly("7");
        assertThat(envelopes.get(1).payload()).contains("ASSIGNED");
    }
}
