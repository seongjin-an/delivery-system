package com.delivery.dispatchengine;

import com.delivery.common.RedisKeys;
import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import com.delivery.common.rider.RiderStateFields;
import com.delivery.common.rider.RiderStatus;
import com.delivery.dispatchengine.kafka.DispatchEventPublisher;
import com.delivery.dispatchengine.offer.AcceptResult;
import com.delivery.dispatchengine.offer.OfferAcceptService;
import com.delivery.dispatchengine.offer.OfferBoard;
import com.delivery.dispatchengine.offer.OfferSnapshot;
import com.delivery.common.dispatch.OfferState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OfferAcceptServiceTest {

    private static final long ORDER_ID = 881520076148810405L;
    private static final long RIDER_ID = 881520076849260058L;
    private static final long OFFER_ID = 881520077000000001L;
    private static final int ATTEMPT = 2;

    @Mock private OfferBoard offerBoard;
    @Mock private DispatchEventPublisher eventPublisher;
    @Mock private StringRedisTemplate redis;
    @Mock private HashOperations<String, Object, Object> hashOps;

    private OfferAcceptService offerAcceptService;

    @BeforeEach
    void setUp() {
        given(redis.opsForHash()).willReturn(hashOps);
        offerAcceptService = new OfferAcceptService(offerBoard, eventPublisher, redis);

        given(offerBoard.findOrderId(OFFER_ID)).willReturn(ORDER_ID);
        given(offerBoard.read(ORDER_ID)).willReturn(
                new OfferSnapshot(OFFER_ID, RIDER_ID, OfferState.ACCEPTED, ATTEMPT, 0L));
    }

    private void givenLuaReturns(AcceptResult result) {
        given(offerBoard.accept(eq(ORDER_ID), eq(OFFER_ID), eq(RIDER_ID), anyLong()))
                .willReturn(result);
    }

    @Test
    void assignsOrderWhenLuaAccepts() {
        givenLuaReturns(AcceptResult.ACCEPTED);

        OfferAcceptService.Assignment assignment = offerAcceptService.accept(OFFER_ID, RIDER_ID);

        assertThat(assignment.orderId()).isEqualTo(ORDER_ID);
        assertThat(assignment.attempt()).isEqualTo(ATTEMPT);
        verify(eventPublisher).publishAssigned(ORDER_ID, RIDER_ID, OFFER_ID, ATTEMPT);
    }

    @Test
    void marksRiderDeliveringWithCurrentOrder() {
        givenLuaReturns(AcceptResult.ACCEPTED);

        offerAcceptService.accept(OFFER_ID, RIDER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hashOps).putAll(eq(RedisKeys.riderState(RIDER_ID)), captor.capture());
        assertThat(captor.getValue())
                .containsEntry(RiderStateFields.STATUS, RiderStatus.DELIVERING.name())
                .containsEntry(RiderStateFields.CURRENT_ORDER_ID, Long.toString(ORDER_ID));
    }

    /**
     * 찜을 여기서 풀면 배달 중인 라이더에게 다른 주문이 붙는다.
     * 배달 완료(OR-04)까지 들고 있어야 해서 지우는 호출이 아예 없어야 한다.
     */
    @Test
    void keepsRiderLockUntilDeliveryCompletes() {
        givenLuaReturns(AcceptResult.ACCEPTED);

        offerAcceptService.accept(OFFER_ID, RIDER_ID);

        verify(redis, never()).delete(RedisKeys.riderLock(RIDER_ID));
    }

    @Test
    void rejectsUnknownOfferAsExpired() {
        given(offerBoard.findOrderId(OFFER_ID)).willReturn(null);

        assertThatThrownBy(() -> offerAcceptService.accept(OFFER_ID, RIDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OFFER_EXPIRED);
    }

    @Test
    void rejectsSecondAcceptOfSameOffer() {
        givenLuaReturns(AcceptResult.ALREADY_TAKEN);

        assertThatThrownBy(() -> offerAcceptService.accept(OFFER_ID, RIDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ALREADY_TAKEN);
    }

    /** 판정에서 진 요청은 라이더 상태도 카프카도 건드리면 안 된다 */
    @Test
    void touchesNothingWhenLuaRejects() {
        givenLuaReturns(AcceptResult.EXPIRED);

        assertThatThrownBy(() -> offerAcceptService.accept(OFFER_ID, RIDER_ID))
                .isInstanceOf(BusinessException.class);

        verify(hashOps, never()).putAll(eq(RedisKeys.riderState(RIDER_ID)), org.mockito.ArgumentMatchers.anyMap());
        verify(eventPublisher, never()).publishAssigned(anyLong(), anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void rejectsOfferBelongingToAnotherRider() {
        givenLuaReturns(AcceptResult.NOT_YOURS);

        assertThatThrownBy(() -> offerAcceptService.accept(OFFER_ID, RIDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_YOUR_OFFER);
    }
}
