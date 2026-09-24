package com.delivery.dispatchengine;

import com.delivery.common.RabbitTopology;
import com.delivery.common.dispatch.DispatchEventPublisher;
import com.delivery.common.dispatch.OfferBoard;
import com.delivery.common.dispatch.OfferDecision;
import com.delivery.common.dispatch.OfferSnapshot;
import com.delivery.common.dispatch.OfferState;
import com.delivery.common.dispatch.RiderLock;
import com.delivery.common.dispatch.RiderState;
import com.delivery.common.event.DispatchOffer;
import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import com.delivery.dispatchengine.offer.OfferResponseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OfferResponseServiceTest {

    private static final long ORDER_ID = 881520076148810405L;
    private static final long RIDER_ID = 881520076849260058L;
    private static final long OFFER_ID = 881520077000000001L;
    private static final int ATTEMPT = 2;

    @Mock private OfferBoard offerBoard;
    @Mock private RiderState riderState;
    @Mock private RiderLock riderLock;
    @Mock private DispatchEventPublisher eventPublisher;
    @Mock private RabbitTemplate rabbitTemplate;

    private OfferResponseService offerResponseService;

    @BeforeEach
    void setUp() {
        offerResponseService = new OfferResponseService(
                offerBoard, riderState, riderLock, eventPublisher, rabbitTemplate);

        given(offerBoard.findOrderId(OFFER_ID)).willReturn(ORDER_ID);
        given(offerBoard.read(ORDER_ID))
                .willReturn(new OfferSnapshot(OFFER_ID, RIDER_ID, OfferState.OFFERED, ATTEMPT, 0L));
    }

    private void givenDecision(OfferState target, OfferDecision decision) {
        given(offerBoard.respond(eq(ORDER_ID), eq(OFFER_ID), eq(RIDER_ID), eq(target), anyLong()))
                .willReturn(decision);
    }

    // ── DE-04 수락 ────────────────────────────────────────────────────────

    @Test
    void assignsOrderWhenLuaAccepts() {
        givenDecision(OfferState.ACCEPTED, OfferDecision.APPLIED);

        OfferResponseService.Assignment assignment = offerResponseService.accept(OFFER_ID, RIDER_ID);

        assertThat(assignment.orderId()).isEqualTo(ORDER_ID);
        assertThat(assignment.attempt()).isEqualTo(ATTEMPT);
        verify(eventPublisher).publishAssigned(ORDER_ID, RIDER_ID, OFFER_ID, ATTEMPT);
    }

    @Test
    void marksRiderDeliveringWithCurrentOrder() {
        givenDecision(OfferState.ACCEPTED, OfferDecision.APPLIED);

        offerResponseService.accept(OFFER_ID, RIDER_ID);

        verify(riderState).markDelivering(RIDER_ID, ORDER_ID);
    }

    /**
     * 찜은 배달 완료(OR-04)까지 들고 있는다. 여기서 풀면 12초 뒤 TTL 이 지났을 때
     * 배달 중인 라이더에게 새 제안이 갈 수 있다.
     */
    @Test
    void keepsRiderLockUntilDeliveryCompletes() {
        givenDecision(OfferState.ACCEPTED, OfferDecision.APPLIED);

        offerResponseService.accept(OFFER_ID, RIDER_ID);

        verify(riderState, never()).release(anyLong(), anyLong(), anyLong());
        verify(riderState, never()).finishDelivery(anyLong(), anyLong());
    }

    @Test
    void rejectsUnknownOfferAsExpired() {
        given(offerBoard.findOrderId(OFFER_ID)).willReturn(null);

        assertThatThrownBy(() -> offerResponseService.accept(OFFER_ID, RIDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OFFER_EXPIRED);
    }

    @Test
    void rejectsSecondAcceptOfSameOffer() {
        givenDecision(OfferState.ACCEPTED, OfferDecision.ALREADY_TAKEN);

        assertThatThrownBy(() -> offerResponseService.accept(OFFER_ID, RIDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_TAKEN);
    }

    /** Lua 가 졌다고 하면 그 뒤로 아무것도 건드리면 안 된다. 이긴 쪽이 이미 진행 중이다 */
    @Test
    void touchesNothingWhenLuaRejects() {
        givenDecision(OfferState.ACCEPTED, OfferDecision.EXPIRED);

        assertThatThrownBy(() -> offerResponseService.accept(OFFER_ID, RIDER_ID))
                .isInstanceOf(BusinessException.class);

        verify(riderState, never()).markDelivering(anyLong(), anyLong());
        verify(eventPublisher, never()).publishAssigned(anyLong(), anyLong(), anyLong(), anyInt());
    }

    @Test
    void rejectsOfferBelongingToAnotherRider() {
        givenDecision(OfferState.ACCEPTED, OfferDecision.NOT_YOURS);

        assertThatThrownBy(() -> offerResponseService.accept(OFFER_ID, RIDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_YOUR_OFFER);
    }

    // ── DE-05 거절 ────────────────────────────────────────────────────────

    @Test
    void releasesRiderWhenOfferIsRejected() {
        givenDecision(OfferState.REJECTED, OfferDecision.APPLIED);

        offerResponseService.reject(OFFER_ID, RIDER_ID);

        verify(riderState).release(RIDER_ID, ORDER_ID, OFFER_ID);
        verify(riderState).countReject(RIDER_ID);
    }

    /**
     * 10초를 더 기다릴 이유가 없으니 타이머 큐가 아니라 DLX 로 바로 넣는다.
     * 여기가 빠지면 거절해도 10초 뒤에나 다음 후보에게 넘어간다.
     */
    @Test
    void handsOverToRelayImmediatelyOnReject() {
        givenDecision(OfferState.REJECTED, OfferDecision.APPLIED);

        offerResponseService.reject(OFFER_ID, RIDER_ID);

        ArgumentCaptor<DispatchOffer> sent = ArgumentCaptor.forClass(DispatchOffer.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitTopology.DISPATCH_DLX), eq(RabbitTopology.RK_OFFER_EXPIRED), sent.capture());

        assertThat(sent.getValue().orderId()).isEqualTo(ORDER_ID);
        assertThat(sent.getValue().offerId()).isEqualTo(OFFER_ID);
        assertThat(sent.getValue().attempt()).isEqualTo(ATTEMPT);
    }

    /** 이미 만료된 제안은 거절도 못 한다. 수락과 같은 판정을 통과해야 한다 */
    @Test
    void rejectsRejectOfExpiredOffer() {
        givenDecision(OfferState.REJECTED, OfferDecision.EXPIRED);

        assertThatThrownBy(() -> offerResponseService.reject(OFFER_ID, RIDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.OFFER_EXPIRED);

        verify(riderState, never()).release(anyLong(), anyLong(), anyLong());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    /**
     * 재제안 요청을 못 넣어도 라이더에게는 성공이어야 한다.
     * 거절은 이미 접수됐고, 10초 뒤 원래 타이머가 이어받는다.
     */
    @Test
    void stillSucceedsWhenHandOverFails() {
        givenDecision(OfferState.REJECTED, OfferDecision.APPLIED);
        willThrow(new IllegalStateException("브로커가 죽었다"))
                .given(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        OfferResponseService.Rejection rejection = offerResponseService.reject(OFFER_ID, RIDER_ID);

        assertThat(rejection.orderId()).isEqualTo(ORDER_ID);
        verify(riderState).release(RIDER_ID, ORDER_ID, OFFER_ID);
    }

    /**
     * 수락 Lua 가 이긴 직후 손님이 취소한 경우(OR-05). 취소 쪽은 라이더가 아직 DELIVERING 이 아니라 못 풀어준다.
     * 여기서 다시 보고 풀지 않으면 취소된 주문 때문에 라이더가 영영 DELIVERING 으로 남는다.
     */
    @Test
    void cancelledRightAfterAcceptReleasesRiderAndSendsNoAssignment() {
        givenDecision(OfferState.ACCEPTED, OfferDecision.APPLIED);
        given(offerBoard.read(ORDER_ID)).willReturn(
                new OfferSnapshot(OFFER_ID, RIDER_ID, OfferState.ACCEPTED, ATTEMPT, 0L),
                new OfferSnapshot(OFFER_ID, RIDER_ID, OfferState.CANCELLED, ATTEMPT, 0L));

        assertThatThrownBy(() -> offerResponseService.accept(OFFER_ID, RIDER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.OFFER_EXPIRED);

        verify(riderState).finishDelivery(RIDER_ID, ORDER_ID);
        verify(riderLock).release(RIDER_ID, ORDER_ID);
        verify(eventPublisher, never()).publishAssigned(anyLong(), anyLong(), anyLong(), anyInt());
    }
}
