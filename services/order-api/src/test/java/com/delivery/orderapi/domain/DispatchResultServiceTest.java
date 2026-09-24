package com.delivery.orderapi.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 판정 자체(어떤 상태에서 바뀌는가)는 DB 가 한다. 그건 OrderRepositoryMysqlTest 가 본다.
 * 여기서는 "바뀌었을 때만, 이벤트 시각으로" 기록을 남기는지만 본다.
 */
@ExtendWith(MockitoExtension.class)
class DispatchResultServiceTest {

    private static final long ORDER_ID = 558668931353510983L;
    private static final long RIDER_ID = 881520076849260058L;
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-23T04:12:41.300Z");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusRecorder statusRecorder;

    @InjectMocks
    private DispatchResultService service;

    @Test
    void recordsTimelineWithEventTimeWhenAssigned() {
        given(orderRepository.assign(eq(ORDER_ID), anyCollection(), eq(RIDER_ID), eq(2), any())).willReturn(1);

        boolean changed = service.markAssigned(ORDER_ID, RIDER_ID, 2, ACCEPTED_AT);

        assertThat(changed).isTrue();
        verify(statusRecorder).record(ORDER_ID, OrderStatus.ASSIGNED, ACCEPTED_AT);
    }

    @Test
    void duplicateEventLeavesNoSecondTimelineRow() {
        given(orderRepository.assign(anyLong(), anyCollection(), anyLong(), anyInt(), any())).willReturn(0);
        given(orderRepository.existsById(ORDER_ID)).willReturn(true);

        boolean changed = service.markAssigned(ORDER_ID, RIDER_ID, 2, ACCEPTED_AT);

        assertThat(changed).isFalse();
        verify(statusRecorder, never()).record(anyLong(), any(), any());
    }

    @Test
    void unknownOrderIsDroppedWithoutThrowing() {
        // 예외를 던지면 공통 에러 핸들러가 3번 재시도하고 DLT 로 보낸다. 없는 주문은 몇 번 해도 없다.
        given(orderRepository.fail(anyLong(), anyCollection(), anyInt(), any())).willReturn(0);
        given(orderRepository.existsById(ORDER_ID)).willReturn(false);

        assertThat(service.markFailed(ORDER_ID, 5, "MAX_ATTEMPTS", ACCEPTED_AT)).isFalse();
    }

    /** 다섯 번 제안하고 실패했으면 조회에도 5 로 보여야 한다. 예전엔 0 이었다 */
    @Test
    void failedOnlyFromBeforeResultAndKeepsAttempt() {
        given(orderRepository.fail(anyLong(), anyCollection(), anyInt(), any())).willReturn(1);

        service.markFailed(ORDER_ID, 5, "MAX_ATTEMPTS", ACCEPTED_AT);

        verify(orderRepository).fail(eq(ORDER_ID),
                eq(java.util.EnumSet.of(OrderStatus.CREATED, OrderStatus.DISPATCHING)),
                eq(5), any());
        verify(statusRecorder).record(ORDER_ID, OrderStatus.FAILED, ACCEPTED_AT);
    }

    @Test
    void missingEventTimeFallsBackToNow() {
        given(orderRepository.transition(anyLong(), anyCollection(), any(), any())).willReturn(1);

        service.markDispatching(ORDER_ID, null);

        verify(statusRecorder).record(eq(ORDER_ID), eq(OrderStatus.DISPATCHING), any(Instant.class));
    }
}
