package com.delivery.orderapi.domain;

import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderCreateServiceTest {

    private static final String KEY = "idem-key-1";
    private static final long EXISTING_ORDER_ID = 558668931353510983L;

    // 강남역 근처 가게 → 삼성역 근처 목적지. 대략 2km 라 20km 제한에 안 걸린다.
    private static final double STORE_LAT = 37.498095;
    private static final double STORE_LNG = 127.027610;
    private static final double DEST_LAT = 37.504198;
    private static final double DEST_LNG = 127.048985;

    @Mock
    private IdempotencyStore idempotencyStore;

    @Mock
    private OrderWriter orderWriter;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderCreateService orderCreateService;

    private OrderCreateService.NewOrder command;

    @BeforeEach
    void setUp() {
        command = new OrderCreateService.NewOrder(
                "store-001", STORE_LAT, STORE_LNG, DEST_LAT, DEST_LNG, 18000);
    }

    private void givenKeyIsFree() {
        given(idempotencyStore.reserve(anyString(), anyLong())).willReturn(true);
        given(orderWriter.write(any())).willAnswer(call -> call.getArgument(0));
    }

    @Test
    void createsOrderWithZoneCalculatedFromStoreCoordinate() {
        givenKeyIsFree();

        OrderCreateService.Result result = orderCreateService.create(KEY, command);

        assertThat(result.isNew()).isTrue();
        assertThat(result.status()).isEqualTo(OrderStatus.CREATED);
        // 가게 좌표(37.498095, 127.027610)를 0.01도 격자로 접은 값
        assertThat(result.zoneId()).isEqualTo("Z3749_12702");
        assertThat(result.orderId()).isPositive();
    }

    /** 20km 검사하느라 어차피 재는 거리를 주문에 같이 저장한다. OR-04 가 다시 계산하지 않게 */
    @Test
    void storesDistanceMeasuredAtCreation() {
        givenKeyIsFree();

        orderCreateService.create(KEY, command);

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderWriter).write(saved.capture());
        assertThat(saved.getValue().getDistanceMeters()).isBetween(1800, 2600);
        assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(saved.getValue().getAttempt()).isZero();
        assertThat(saved.getValue().getRiderId()).isNull();
    }

    @Test
    void rejectsMissingIdempotencyKey() {
        assertThatThrownBy(() -> orderCreateService.create("  ", command))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MISSING_IDEMPOTENCY_KEY);

        verify(orderWriter, never()).write(any());
    }

    @Test
    void rejectsCoordinateOutsideKorea() {
        OrderCreateService.NewOrder tokyo = new OrderCreateService.NewOrder(
                "store-001", 35.6812, 139.7671, DEST_LAT, DEST_LNG, 18000);

        assertThatThrownBy(() -> orderCreateService.create(KEY, tokyo))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_COORDINATE);

        verify(orderWriter, never()).write(any());
    }

    /** 서울 가게에서 강릉으로 배달시키는 요청. 대략 160km 라 걸러져야 한다 */
    @Test
    void rejectsDeliveryFartherThanTwentyKilometers() {
        OrderCreateService.NewOrder tooFar = new OrderCreateService.NewOrder(
                "store-001", STORE_LAT, STORE_LNG, 37.7519, 128.8761, 18000);

        assertThatThrownBy(() -> orderCreateService.create(KEY, tooFar))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        verify(orderWriter, never()).write(any());
    }

    /** 멱등키가 이미 잡혀 있으면 새 주문을 만들지 않고 앞서 만든 걸 그대로 돌려준다 */
    @Test
    void returnsExistingOrderWhenIdempotencyKeyWasAlreadyUsed() {
        Order existing = Order.create(EXISTING_ORDER_ID, "store-001",
                STORE_LAT, STORE_LNG, DEST_LAT, DEST_LNG, "Z3749_12702", 18000, 2000, Instant.now());
        given(idempotencyStore.reserve(anyString(), anyLong())).willReturn(false);
        given(idempotencyStore.findOrderId(KEY)).willReturn(OptionalLong.of(EXISTING_ORDER_ID));
        given(orderRepository.findById(EXISTING_ORDER_ID)).willReturn(Optional.of(existing));

        OrderCreateService.Result result = orderCreateService.create(KEY, command);

        assertThat(result.isNew()).isFalse();
        assertThat(result.orderId()).isEqualTo(EXISTING_ORDER_ID);
        verify(orderWriter, never()).write(any());
    }

    /**
     * 앞 요청이 키만 잡고 아직 커밋을 안 끝낸 짧은 순간. 404 를 주면 앱이 새로 주문을 만들어서
     * 중복이 생기니까, 잡혀 있는 orderId 를 그대로 알려준다.
     */
    @Test
    void returnsReservedOrderIdWhenFirstRequestHasNotCommittedYet() {
        given(idempotencyStore.reserve(anyString(), anyLong())).willReturn(false);
        given(idempotencyStore.findOrderId(KEY)).willReturn(OptionalLong.of(EXISTING_ORDER_ID));
        given(orderRepository.findById(EXISTING_ORDER_ID)).willReturn(Optional.empty());

        OrderCreateService.Result result = orderCreateService.create(KEY, command);

        assertThat(result.isNew()).isFalse();
        assertThat(result.orderId()).isEqualTo(EXISTING_ORDER_ID);
        assertThat(result.status()).isEqualTo(OrderStatus.CREATED);
    }

    /**
     * 저장이 실패했는데 키를 안 놓으면, 손님이 다시 눌러도 한 시간 동안 "이미 처리됨" 으로 막힌다.
     * 정작 주문은 어디에도 없는 상태로.
     */
    @Test
    void releasesIdempotencyKeyWhenWriteFails() {
        given(idempotencyStore.reserve(anyString(), anyLong())).willReturn(true);
        given(orderWriter.write(any())).willThrow(new IllegalStateException("DB 가 죽었다"));

        assertThatThrownBy(() -> orderCreateService.create(KEY, command))
                .isInstanceOf(IllegalStateException.class);

        verify(idempotencyStore).release(KEY);
    }
}
