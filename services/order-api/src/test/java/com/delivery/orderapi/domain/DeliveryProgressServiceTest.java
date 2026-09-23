package com.delivery.orderapi.domain;

import com.delivery.common.KafkaTopics;
import com.delivery.common.dispatch.CandidateList;
import com.delivery.common.dispatch.OfferBoard;
import com.delivery.common.dispatch.RiderLock;
import com.delivery.common.dispatch.RiderState;
import com.delivery.common.event.DeliveryCompleted;
import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import com.delivery.orderapi.outbox.OutboxAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 영향 행 수가 0 일 때 어떻게 가르는지와, 레디스 정리를 언제 하는지를 본다.
 * UPDATE 조건 자체는 OrderRepositoryMysqlTest 가, Lua 판정은 DispatchScriptsRedisTest 가 본다.
 */
class DeliveryProgressServiceTest {

    private static final long ORDER_ID = 558668931353510983L;
    private static final long RIDER_ID = 881520076849260058L;
    private static final Instant CREATED_AT = Instant.parse("2026-09-23T04:00:00Z");
    private static final Instant ASSIGNED_AT = Instant.parse("2026-09-23T04:00:08Z");

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final OrderStatusHistoryRepository historyRepository = mock(OrderStatusHistoryRepository.class);
    private final OrderStatusRecorder statusRecorder = mock(OrderStatusRecorder.class);
    private final OutboxAppender outboxAppender = mock(OutboxAppender.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    private final RiderState riderState = mock(RiderState.class);
    private final RiderLock riderLock = mock(RiderLock.class);
    private final OfferBoard offerBoard = mock(OfferBoard.class);
    private final CandidateList candidateList = mock(CandidateList.class);

    private final DeliveryProgressService service = new DeliveryProgressService(
            orderRepository, historyRepository, statusRecorder, outboxAppender, new TransactionTemplate(txManager),
            riderState, riderLock, offerBoard, candidateList);

    @BeforeEach
    void transactionsJustRun() {
        given(txManager.getTransaction(any())).willReturn(new SimpleTransactionStatus());
    }

    // ── OR-03 예외 표 ─────────────────────────────────────────────────────

    @Test
    void pickUpPublishesStatusThroughOutbox() {
        given(orderRepository.advance(eq(ORDER_ID), eq(RIDER_ID), eq(OrderStatus.ASSIGNED), eq(OrderStatus.PICKED_UP), anyInstant()))
                .willReturn(1);

        DeliveryProgressService.Progress progress = service.pickUp(ORDER_ID, RIDER_ID);

        assertThat(progress.changed()).isTrue();
        verify(statusRecorder).record(eq(ORDER_ID), eq(OrderStatus.PICKED_UP), any());
        verify(outboxAppender).append(eq(KafkaTopics.ORDER_STATUS), eq(ORDER_ID), any(), any(), any());
    }

    @Test
    void secondPickUpBySameRiderIsOk() {
        givenNoRowChanged(order(OrderStatus.PICKED_UP, RIDER_ID));

        DeliveryProgressService.Progress progress = service.pickUp(ORDER_ID, RIDER_ID);

        assertThat(progress.changed()).isFalse();
        assertThat(progress.status()).isEqualTo(OrderStatus.PICKED_UP);
        verify(outboxAppender, never()).append(any(), anyLong(), any(), any(), any());
    }

    @Test
    void otherRiderGets403EvenIfAlreadyPickedUp() {
        // 상태부터 보면 "이미 PICKED_UP 이네, 200" 이 나가서 남의 앱에 픽업 완료가 뜬다
        givenNoRowChanged(order(OrderStatus.PICKED_UP, RIDER_ID));

        assertThatThrownBy(() -> service.pickUp(ORDER_ID, RIDER_ID + 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.NOT_YOUR_ORDER);
    }

    @Test
    void pickUpBeforeAssignmentIs409() {
        givenNoRowChanged(order(OrderStatus.DISPATCHING, null));

        assertThatThrownBy(() -> service.pickUp(ORDER_ID, RIDER_ID))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    void unknownOrderIs404() {
        given(orderRepository.advance(anyLong(), anyLong(), any(), any(), any())).willReturn(0);
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.pickUp(ORDER_ID, RIDER_ID))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND);
    }

    // ── OR-04 ─────────────────────────────────────────────────────────────

    @Test
    void completeWritesDeliveryCompletedThenReleasesRiderAfterCommit() {
        given(orderRepository.advance(eq(ORDER_ID), eq(RIDER_ID), eq(OrderStatus.PICKED_UP), eq(OrderStatus.DELIVERED), anyInstant()))
                .willReturn(1);
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order(OrderStatus.DELIVERED, RIDER_ID)));
        given(historyRepository.findFirstByOrderIdAndStatusOrderByIdAsc(ORDER_ID, OrderStatus.ASSIGNED))
                .willReturn(Optional.of(OrderStatusHistory.of(ORDER_ID, OrderStatus.ASSIGNED, ASSIGNED_AT)));

        service.complete(ORDER_ID, RIDER_ID);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxAppender).append(eq(KafkaTopics.DELIVERY_COMPLETED), eq(ORDER_ID), any(), payload.capture(), any());
        DeliveryCompleted event = (DeliveryCompleted) payload.getValue();
        assertThat(event.riderId()).isEqualTo(RIDER_ID);
        assertThat(event.assignedAt()).isEqualTo(ASSIGNED_AT);
        assertThat(event.priceKrw()).isEqualTo(18000);
        assertThat(event.distanceMeters()).isEqualTo(2004);

        // 커밋이 먼저, 레디스가 나중. 반대면 라이더가 풀린 뒤 DB 가 롤백되는 순간이 생긴다
        InOrder order = inOrder(txManager, riderState, riderLock, candidateList);
        order.verify(txManager).commit(any());
        order.verify(riderState).finishDelivery(RIDER_ID, ORDER_ID);
        order.verify(riderLock).release(RIDER_ID, ORDER_ID);
        order.verify(candidateList).clear(ORDER_ID);
    }

    @Test
    void retriedCompleteStillCleansUpRedis() {
        // 첫 요청이 커밋 뒤 레디스에서 실패했을 수 있다. 두 번째 요청이 마저 끝내야 한다
        givenNoRowChanged(order(OrderStatus.DELIVERED, RIDER_ID));

        DeliveryProgressService.Progress progress = service.complete(ORDER_ID, RIDER_ID);

        assertThat(progress.changed()).isFalse();
        verify(outboxAppender, never()).append(any(), anyLong(), any(), any(), any());
        verify(riderState).finishDelivery(RIDER_ID, ORDER_ID);
    }

    @Test
    void completeBeforePickUpIs409AndLeavesRiderAlone() {
        givenNoRowChanged(order(OrderStatus.ASSIGNED, RIDER_ID));

        assertThatThrownBy(() -> service.complete(ORDER_ID, RIDER_ID))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INVALID_STATE);
        verify(riderState, never()).finishDelivery(anyLong(), anyLong());
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private void givenNoRowChanged(Order order) {
        given(orderRepository.advance(anyLong(), anyLong(), any(), any(), any())).willReturn(0);
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
    }

    private static Order order(OrderStatus status, Long riderId) {
        Order order = Order.create(ORDER_ID, "store-001",
                37.498095, 127.027610, 37.504198, 127.048985,
                "Z3749_12702", 18000, 2004, CREATED_AT);
        // 상태와 라이더는 UPDATE 로만 바뀌어서 엔티티에 setter 가 없다. 테스트에서만 밀어 넣는다
        ReflectionTestUtils.setField(order, "status", status);
        ReflectionTestUtils.setField(order, "riderId", riderId);
        return order;
    }

    private static Instant anyInstant() {
        return any(Instant.class);
    }
}
