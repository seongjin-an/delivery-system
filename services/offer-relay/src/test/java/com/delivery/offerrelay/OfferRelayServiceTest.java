package com.delivery.offerrelay;

import com.delivery.common.dispatch.DispatchEventPublisher;
import com.delivery.common.dispatch.DispatchLease;
import com.delivery.common.dispatch.ExpiryDecision;
import com.delivery.common.dispatch.OfferBoard;
import com.delivery.common.dispatch.OfferSender;
import com.delivery.common.dispatch.OfferState;
import com.delivery.common.dispatch.RiderState;
import com.delivery.common.event.DispatchOffer;
import com.delivery.offerrelay.config.RelayProperties;
import com.delivery.offerrelay.relay.OfferRelayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OfferRelayServiceTest {

    private static final long ORDER_ID = 881520076148810405L;
    private static final long RIDER_ID = 881520076849260058L;
    private static final long NEXT_RIDER_ID = 881520076849260059L;
    private static final long OFFER_ID = 881520077000000001L;
    private static final int MAX_ATTEMPTS = 5;

    @Mock private DispatchLease dispatchLease;
    @Mock private OfferBoard offerBoard;
    @Mock private OfferSender offerSender;
    @Mock private RiderState riderState;
    @Mock private DispatchEventPublisher eventPublisher;

    private OfferRelayService relayService;

    private static DispatchOffer expired(int attempt) {
        return new DispatchOffer(OFFER_ID, ORDER_ID, RIDER_ID, attempt, Instant.now());
    }

    private static DispatchOffer nextOffer(int attempt) {
        return new DispatchOffer(OFFER_ID + 1, ORDER_ID, NEXT_RIDER_ID, attempt, Instant.now());
    }

    @BeforeEach
    void setUp() {
        relayService = new OfferRelayService(dispatchLease, offerBoard, offerSender,
                riderState, eventPublisher, new RelayProperties(MAX_ATTEMPTS),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        ReflectionTestUtils.setField(relayService, "instanceId", "offer-relay:8094");

        given(dispatchLease.acquire(anyLong(), anyString())).willAnswer(c -> c.getArgument(1));
        given(dispatchLease.release(anyLong(), anyString())).willReturn(true);
        given(riderState.release(anyLong(), anyLong(), anyLong())).willReturn(true);
    }

    private void givenExpiry(ExpiryDecision decision) {
        given(offerBoard.expire(eq(ORDER_ID), eq(OFFER_ID), anyLong())).willReturn(decision);
    }

    // ── 정상 경로 ─────────────────────────────────────────────────────────

    @Test
    void offersToNextCandidateWithIncreasedAttempt() {
        givenExpiry(ExpiryDecision.RETRY);
        given(offerSender.offerToNextCandidate(ORDER_ID, 3)).willReturn(nextOffer(3));

        relayService.relay(expired(2));

        verify(offerSender).offerToNextCandidate(ORDER_ID, 3);
    }

    /**
     * 다음 후보를 찾기 전에 직전 라이더를 놓아줘야 한다.
     * 순서가 뒤집히면 이 라이더가 자기 자신의 다음 후보가 될 수 없다 — 후보 목록에는 남아
     * 있는데 찜이 걸려 있어서 건너뛰어진다. 라이더가 둘뿐인 존에서는 곧바로 배차 실패다.
     */
    @Test
    void releasesPreviousRiderBeforeLookingForNext() {
        givenExpiry(ExpiryDecision.RETRY);
        given(offerSender.offerToNextCandidate(anyLong(), anyInt())).willReturn(nextOffer(2));

        relayService.relay(expired(1));

        InOrder order = inOrder(riderState, offerSender);
        order.verify(riderState).release(RIDER_ID, ORDER_ID, OFFER_ID);
        order.verify(offerSender).offerToNextCandidate(anyLong(), anyInt());
    }

    // ── 버려야 하는 경우 (기능 정의서 RE-02 규칙 1~3) ─────────────────────

    /** 펜싱 규칙. 이미 다음 후보로 넘어간 뒤 도착한 옛 타이머다 */
    @Test
    void dropsStaleMessageThatLostFencing() {
        givenExpiry(ExpiryDecision.STALE);

        relayService.relay(expired(1));

        verify(offerSender, never()).offerToNextCandidate(anyLong(), anyInt());
        verify(riderState, never()).release(anyLong(), anyLong(), anyLong());
    }

    /** 9.9초에 수락, 10.0초에 만료. 수락이 이긴 경우다 */
    @Test
    void dropsMessageWhenRiderAlreadyAccepted() {
        givenExpiry(ExpiryDecision.ACCEPTED);

        relayService.relay(expired(1));

        verify(offerSender, never()).offerToNextCandidate(anyLong(), anyInt());
        verify(eventPublisher, never()).publishFailed(anyLong(), anyInt(), anyString());
    }

    @Test
    void dropsMessageWhenOrderIsClosed() {
        givenExpiry(ExpiryDecision.CLOSED);

        relayService.relay(expired(1));

        verify(offerSender, never()).offerToNextCandidate(anyLong(), anyInt());
    }

    @Test
    void dropsMessageWhenBoardIsGone() {
        givenExpiry(ExpiryDecision.GONE);

        relayService.relay(expired(1));

        verify(offerSender, never()).offerToNextCandidate(anyLong(), anyInt());
    }

    // ── 후보 소진 (규칙 4·6) ──────────────────────────────────────────────

    /**
     * 완료 조건 그대로. attempt 5까지 제안했는데 아무도 안 받으면 거기서 끝낸다.
     * 10초짜리 제안 다섯 번이면 50초다.
     */
    @Test
    void failsDispatchAfterMaxAttempts() {
        givenExpiry(ExpiryDecision.RETRY);

        relayService.relay(expired(MAX_ATTEMPTS));

        verify(offerBoard).writeState(ORDER_ID, OfferState.FAILED);
        verify(eventPublisher).publishFailed(ORDER_ID, 5, "MAX_ATTEMPTS");
        verify(offerSender, never()).offerToNextCandidate(anyLong(), anyInt());
    }

    /** 다섯 번을 다 쓰기 전이라도 후보 목록이 비면 거기서 끝난다 */
    @Test
    void failsDispatchWhenCandidateListRunsOut() {
        givenExpiry(ExpiryDecision.RETRY);
        given(offerSender.offerToNextCandidate(anyLong(), anyInt())).willReturn(null);

        relayService.relay(expired(2));

        verify(offerBoard).writeState(ORDER_ID, OfferState.FAILED);
        verify(eventPublisher).publishFailed(eq(ORDER_ID), anyInt(), eq("NO_CANDIDATE_LEFT"));
    }

    /** 마지막 제안이 나가기 전에 라이더는 반드시 놓여나야 한다. 안 그러면 12초를 논다 */
    @Test
    void releasesRiderEvenWhenDispatchFails() {
        givenExpiry(ExpiryDecision.RETRY);

        relayService.relay(expired(MAX_ATTEMPTS));

        verify(riderState).release(RIDER_ID, ORDER_ID, OFFER_ID);
    }

    // ── 배차 리스 ─────────────────────────────────────────────────────────

    /**
     * dispatch-engine 이 좀비 판정으로 같은 주문을 다시 배차 중이거나, 다른 relay 인스턴스가
     * 같은 메시지를 잡고 있다. 같이 진행하면 후보를 두 명 꺼내서 제안이 두 개 나간다.
     */
    @Test
    void skipsWhenAnotherInstanceHoldsTheLease() {
        given(dispatchLease.acquire(anyLong(), anyString())).willReturn(null);

        relayService.relay(expired(1));

        verify(offerBoard, never()).expire(anyLong(), anyLong(), anyLong());
        verify(offerSender, never()).offerToNextCandidate(anyLong(), anyInt());
    }

    @Test
    void releasesLeaseEvenWhenRelayFails() {
        givenExpiry(ExpiryDecision.RETRY);
        given(offerSender.offerToNextCandidate(anyLong(), anyInt()))
                .willThrow(new IllegalStateException("레디스가 죽었다"));

        try {
            relayService.relay(expired(1));
        } catch (IllegalStateException ignored) {
            // 예외는 위로 올라가야 래빗엠큐가 ack 하지 않는다
        }

        verify(dispatchLease).release(eq(ORDER_ID), anyString());
    }
}
