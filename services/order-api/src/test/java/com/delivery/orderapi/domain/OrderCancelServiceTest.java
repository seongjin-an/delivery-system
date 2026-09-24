package com.delivery.orderapi.domain;

import com.delivery.common.KafkaTopics;
import com.delivery.common.dispatch.CandidateList;
import com.delivery.common.dispatch.DispatchLease;
import com.delivery.common.dispatch.OfferBoard;
import com.delivery.common.dispatch.OfferState;
import com.delivery.common.dispatch.RiderLock;
import com.delivery.common.dispatch.RiderState;
import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import com.delivery.orderapi.outbox.OutboxAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 취소 순서와 누구를 풀어주는지를 본다. Lua 판정은 DispatchScriptsRedisTest 가, UPDATE 조건은 MySQL 테스트가 본다.
 */
class OrderCancelServiceTest {

    private static final long ORDER_ID = 558668931353510983L;
    private static final long RIDER_ID = 881520076849260058L;
    private static final long OFFER_ID = 890391265973758795L;

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderStatusRecorder statusRecorder = mock(OrderStatusRecorder.class);
    private final OutboxAppender outboxAppender = mock(OutboxAppender.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final DispatchLease lease = mock(DispatchLease.class);
    private final OfferBoard offerBoard = mock(OfferBoard.class);
    private final RiderState riderState = mock(RiderState.class);
    private final RiderLock riderLock = mock(RiderLock.class);
    private final CandidateList candidateList = mock(CandidateList.class);

    private final OrderCancelService service = new OrderCancelService(orderRepository, statusRecorder,
            outboxAppender, new TransactionTemplate(txManager), lease, offerBoard, riderState, riderLock, candidateList);

    @BeforeEach
    void setUp() {
        given(txManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
        given(lease.acquire(eq(ORDER_ID), anyString())).willAnswer(inv -> inv.getArgument(1));
        given(lease.release(eq(ORDER_ID), anyString())).willReturn(true);
        given(orderRepository.transition(eq(ORDER_ID), anyCollection(), eq(OrderStatus.CANCELLED), any())).willReturn(1);
        given(offerBoard.cancel(eq(ORDER_ID), anyLong())).willReturn(new OfferBoard.Cancellation(null, 0, 0));
    }

    @Test
    void takesLeaseBeforeTouchingAnythingAndReleasesItAtTheEnd() {
        givenOrder(OrderStatus.DISPATCHING, null);

        service.cancel(ORDER_ID);

        // 리스 → DB → 보드 → 리스 해제. 보드를 먼저 바꾸고 리스를 잡으면, 그 사이 dispatch-engine 이 OFFERED 로 덮어쓴다
        InOrder order = inOrder(lease, orderRepository, offerBoard);
        order.verify(lease).acquire(eq(ORDER_ID), anyString());
        order.verify(orderRepository).transition(eq(ORDER_ID), anyCollection(), eq(OrderStatus.CANCELLED), any());
        order.verify(offerBoard).cancel(eq(ORDER_ID), anyLong());
        order.verify(lease).release(eq(ORDER_ID), anyString());
        verify(outboxAppender).append(eq(KafkaTopics.ORDER_STATUS), eq(ORDER_ID), any(), any(), any());
    }

    @Test
    void releasesRiderWhoWasHoldingTheOffer() {
        givenOrder(OrderStatus.DISPATCHING, null);
        given(offerBoard.cancel(eq(ORDER_ID), anyLong()))
                .willReturn(new OfferBoard.Cancellation(OfferState.OFFERED, RIDER_ID, OFFER_ID));

        service.cancel(ORDER_ID);

        verify(riderState).release(RIDER_ID, ORDER_ID, OFFER_ID);
        verify(riderState, never()).finishDelivery(anyLong(), anyLong());
    }

    @Test
    void releasesAssignedRiderFromDbEvenIfBoardExpired() {
        // 배달이 10분 넘게 걸리면 보드는 TTL 로 사라져 있다. 라이더는 DB 에서 찾는다
        givenOrder(OrderStatus.PICKED_UP, RIDER_ID);

        service.cancel(ORDER_ID);

        verify(riderState).finishDelivery(RIDER_ID, ORDER_ID);
        verify(riderLock).release(RIDER_ID, ORDER_ID);
    }

    @Test
    void releasesRiderWhoAcceptedBeforeOr07CaughtUp() {
        // 수락은 됐는데 dispatch.assigned 가 아직 order-api 에 안 와서 DB 엔 라이더가 없다
        givenOrder(OrderStatus.DISPATCHING, null);
        given(offerBoard.cancel(eq(ORDER_ID), anyLong()))
                .willReturn(new OfferBoard.Cancellation(OfferState.ACCEPTED, RIDER_ID, OFFER_ID));

        service.cancel(ORDER_ID);

        verify(riderState).finishDelivery(RIDER_ID, ORDER_ID);
    }

    @Test
    void deliveredOrderIs409AndLeaseIsStillReleased() {
        givenOrder(OrderStatus.DELIVERED, RIDER_ID);

        assertThatThrownBy(() -> service.cancel(ORDER_ID))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INVALID_STATE);

        verify(offerBoard, never()).cancel(anyLong(), anyLong());
        verify(lease).release(eq(ORDER_ID), anyString());
    }

    @Test
    void secondCancelIs200AndCleansUpAgain() {
        // 첫 요청이 DB 커밋 뒤 레디스에서 실패했을 수 있다. 두 번째 요청이 마저 끝낸다
        givenOrder(OrderStatus.CANCELLED, RIDER_ID);

        OrderCancelService.Cancelled result = service.cancel(ORDER_ID);

        assertThat(result.changed()).isFalse();
        verify(orderRepository, never()).transition(anyLong(), anyCollection(), any(), any());
        verify(riderState).finishDelivery(RIDER_ID, ORDER_ID);
    }

    @Test
    void busyDispatchIs503AndDbIsUntouched() {
        given(lease.acquire(eq(ORDER_ID), anyString())).willReturn(null);

        assertThatThrownBy(() -> service.cancel(ORDER_ID))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.DISPATCH_BUSY);

        verify(orderRepository, never()).findById(anyLong());
    }

    private void givenOrder(OrderStatus status, Long riderId) {
        Order order = Order.create(ORDER_ID, "store-001",
                37.498095, 127.027610, 37.504198, 127.048985,
                "Z3749_12702", 18000, 2004, Instant.parse("2026-09-24T04:00:00Z"));
        ReflectionTestUtils.setField(order, "status", status);
        ReflectionTestUtils.setField(order, "riderId", riderId);
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
    }
}
