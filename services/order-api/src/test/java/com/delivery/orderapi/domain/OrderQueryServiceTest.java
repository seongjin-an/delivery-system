package com.delivery.orderapi.domain;

import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class OrderQueryServiceTest {

    private static final long ORDER_ID = 881520076148810405L;
    private static final Instant CREATED_AT = Instant.parse("2026-08-23T04:12:33.482Z");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusHistoryRepository historyRepository;

    @InjectMocks
    private OrderQueryService orderQueryService;

    private static Order sampleOrder() {
        return Order.create(ORDER_ID, "store-001",
                37.498095, 127.027610, 37.504198, 127.048985,
                "Z3749_12702", 18000, 2004, CREATED_AT);
    }

    @Test
    void returnsOrderWithTimelineInRecordedOrder() {
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(sampleOrder()));
        given(historyRepository.findByOrderIdOrderByIdAsc(ORDER_ID)).willReturn(List.of(
                OrderStatusHistory.of(ORDER_ID, OrderStatus.CREATED, CREATED_AT),
                OrderStatusHistory.of(ORDER_ID, OrderStatus.DISPATCHING, CREATED_AT.plusMillis(230)),
                OrderStatusHistory.of(ORDER_ID, OrderStatus.ASSIGNED, CREATED_AT.plusSeconds(8))));

        OrderQueryService.OrderDetail detail = orderQueryService.findDetail(ORDER_ID);

        assertThat(detail.orderId()).isEqualTo(ORDER_ID);
        assertThat(detail.timeline())
                .extracting(OrderQueryService.TimelineEntry::status)
                .containsExactly(OrderStatus.CREATED, OrderStatus.DISPATCHING, OrderStatus.ASSIGNED);
        assertThat(detail.timeline().get(0).at()).isEqualTo(CREATED_AT);
    }

    /** 배차 전이면 riderId 가 비어 있고 attempt 는 0 이다 */
    @Test
    void returnsNullRiderIdBeforeAssignment() {
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(sampleOrder()));
        given(historyRepository.findByOrderIdOrderByIdAsc(ORDER_ID)).willReturn(List.of(
                OrderStatusHistory.of(ORDER_ID, OrderStatus.CREATED, CREATED_AT)));

        OrderQueryService.OrderDetail detail = orderQueryService.findDetail(ORDER_ID);

        assertThat(detail.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(detail.riderId()).isNull();
        assertThat(detail.attempt()).isZero();
    }

    @Test
    void throwsOrderNotFoundWhenOrderIsAbsent() {
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderQueryService.findDetail(ORDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }
}
