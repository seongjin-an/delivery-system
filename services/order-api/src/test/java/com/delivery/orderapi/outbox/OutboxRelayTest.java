package com.delivery.orderapi.outbox;

import com.delivery.common.KafkaTopics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final long ORDER_ID = 881520076148810405L;
    private static final String KEY = "881520076148810405";
    private static final String PAYLOAD = "{\"orderId\":881520076148810405}";

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks
    private OutboxRelay outboxRelay;

    private static OutboxMessage message() {
        return OutboxMessage.pending(ORDER_ID, KafkaTopics.ORDER_CREATED, KEY, PAYLOAD, Instant.now());
    }

    private static CompletableFuture<SendResult<String, String>> ok() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, String>> failed() {
        return CompletableFuture.failedFuture(new IllegalStateException("브로커가 안 붙는다"));
    }

    @Test
    void marksPublishedOnlyAfterBrokerAcknowledges() {
        OutboxMessage message = message();
        given(outboxRepository.lockUnpublished(anyInt())).willReturn(List.of(message));
        given(kafkaTemplate.send(eq(KafkaTopics.ORDER_CREATED), eq(KEY), eq(PAYLOAD))).willReturn(ok());

        int published = outboxRelay.relayBatch(100);

        assertThat(published).isEqualTo(1);
        assertThat(message.getPublishedAt()).isNotNull();
    }

    /**
     * 실패한 행은 published_at 이 비어 있어야 다음 주기에 다시 집힌다.
     * 여기서 포기해버리면 "DB 엔 주문이 있는데 배차가 안 걸린" 주문이 생긴다.
     */
    @Test
    void leavesRowUnpublishedWhenSendFails() {
        OutboxMessage message = message();
        given(outboxRepository.lockUnpublished(anyInt())).willReturn(List.of(message));
        given(kafkaTemplate.send(eq(KafkaTopics.ORDER_CREATED), eq(KEY), eq(PAYLOAD))).willReturn(failed());

        int published = outboxRelay.relayBatch(100);

        assertThat(published).isZero();
        assertThat(message.getPublishedAt()).isNull();
        assertThat(message.getAttemptCount()).isEqualTo(1);
    }

    /** 한 건이 실패해도 나머지는 나가야 한다. 한 놈 때문에 배치 전체가 멈추면 안 된다 */
    @Test
    void publishesRemainingMessagesWhenOneFails() {
        OutboxMessage failing = message();
        OutboxMessage succeeding = OutboxMessage.pending(
                ORDER_ID + 1, KafkaTopics.ORDER_CREATED, "881520076148810406", PAYLOAD, Instant.now());
        given(outboxRepository.lockUnpublished(anyInt())).willReturn(List.of(failing, succeeding));
        given(kafkaTemplate.send(eq(KafkaTopics.ORDER_CREATED), eq(KEY), eq(PAYLOAD))).willReturn(failed());
        given(kafkaTemplate.send(eq(KafkaTopics.ORDER_CREATED), eq("881520076148810406"), eq(PAYLOAD)))
                .willReturn(ok());

        int published = outboxRelay.relayBatch(100);

        assertThat(published).isEqualTo(1);
        assertThat(failing.getPublishedAt()).isNull();
        assertThat(succeeding.getPublishedAt()).isNotNull();
    }

    /** 보낼 게 없으면 카프카를 건드리지도 않는다. 200ms 마다 도는 자리라 헛일을 줄인다 */
    @Test
    void doesNothingWhenOutboxIsEmpty() {
        given(outboxRepository.lockUnpublished(anyInt())).willReturn(List.of());

        assertThat(outboxRelay.relayBatch(100)).isZero();
        verify(kafkaTemplate, never()).flush();
    }
}
