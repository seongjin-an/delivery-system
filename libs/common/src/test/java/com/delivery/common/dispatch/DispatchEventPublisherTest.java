package com.delivery.common.dispatch;

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

        assertThatThrownBy(() -> publisher.publishFailed(1L, "MAX_ATTEMPTS"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(KafkaTopics.DISPATCH_FAILED);
    }

    @Test
    void acknowledgedSendReturnsNormally() {
        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThatCode(() -> publisher.publishAssigned(1L, 2L, 3L, 1)).doesNotThrowAnyException();
    }
}
