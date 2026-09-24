package com.delivery.orderapi.kafka;

import com.delivery.common.KafkaTopics;
import com.delivery.common.dispatch.DispatchEventPublisher;
import com.delivery.orderapi.domain.DispatchResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 보내는 쪽(dispatch-engine, offer-relay 가 쓰는 DispatchEventPublisher)이 만든 JSON 을
 * 받는 쪽이 그대로 읽는지 본다.
 *
 * <p>손으로 JSON 을 적어서 테스트하면 "내가 적은 JSON 을 내가 읽는다" 만 확인하게 된다.
 * 발행하는 쪽이 필드 이름을 바꾸면 이 테스트가 깨져야 한다.
 */
class DispatchResultListenerTest {

    private static final long ORDER_ID = 558668931353510983L;
    private static final long RIDER_ID = 881520076849260058L;
    private static final long OFFER_ID = 890391265973758795L;

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final DispatchEventPublisher publisher = new DispatchEventPublisher(kafkaTemplate);

    private final DispatchResultService service = mock(DispatchResultService.class);
    private final DispatchResultListener listener = new DispatchResultListener(service);
    private final Acknowledgment ack = mock(Acknowledgment.class);

    /** 브로커가 바로 받아줬다고 치자. 발행 쪽은 이제 이 응답을 기다린다 */
    @BeforeEach
    void brokerAcceptsEverything() {
        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    @Test
    void readsAssignedAsPublished() {
        publisher.publishAssigned(ORDER_ID, RIDER_ID, OFFER_ID, 2);

        listener.onAssigned(sent(KafkaTopics.DISPATCH_ASSIGNED), ack);

        verify(service).markAssigned(eq(ORDER_ID), eq(RIDER_ID), eq(2), notNull());
        verify(ack).acknowledge();
    }

    @Test
    void readsFailedAsPublished() {
        publisher.publishFailed(ORDER_ID, 5, "MAX_ATTEMPTS");

        listener.onFailed(sent(KafkaTopics.DISPATCH_FAILED), ack);

        verify(service).markFailed(eq(ORDER_ID), eq(5), eq("MAX_ATTEMPTS"), notNull());
    }

    /** attempt 를 싣기 전에 나간 이벤트가 토픽에 남아 있다. 새 order-api 가 그걸 읽다 죽으면 안 된다 */
    @Test
    void readsOldFailedEventWithoutAttemptAsZero() {
        listener.onFailed("{\"orderId\":" + ORDER_ID + ",\"reason\":\"MAX_ATTEMPTS\",\"at\":\"2026-09-23T00:15:45.368Z\"}", ack);

        verify(service).markFailed(eq(ORDER_ID), eq(0), eq("MAX_ATTEMPTS"), notNull());
        verify(ack).acknowledge();
    }

    @Test
    void readsDispatchingFromOrderStatus() {
        publisher.publishDispatching(ORDER_ID);

        listener.onStatus(sent(KafkaTopics.ORDER_STATUS), ack);

        verify(service).markDispatching(eq(ORDER_ID), notNull());
        verify(ack).acknowledge();
    }

    @Test
    void ignoresOtherStatusesOnOrderStatusButStillAcks() {
        // ASSIGNED 는 attempt 가 실린 dispatch.assigned 로 받는다. order.status 의 ASSIGNED 는 버린다.
        publisher.publishAssigned(ORDER_ID, RIDER_ID, OFFER_ID, 2);

        listener.onStatus(sent(KafkaTopics.ORDER_STATUS), ack);

        verify(service, never()).markDispatching(anyLong(), any());
        verify(ack).acknowledge();
    }

    /** 발행한 페이로드 중 해당 토픽으로 나간 걸 꺼낸다 */
    private String sent(String topic) {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate, org.mockito.Mockito.atLeastOnce()).send(eq(topic), anyString(), payload.capture());
        return payload.getValue();
    }
}
