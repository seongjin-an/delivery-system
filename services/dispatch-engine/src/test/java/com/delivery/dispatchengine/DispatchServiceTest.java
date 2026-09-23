package com.delivery.dispatchengine;

import com.delivery.common.dispatch.CandidateList;
import com.delivery.common.dispatch.DispatchEventPublisher;
import com.delivery.common.dispatch.DispatchLease;
import com.delivery.common.dispatch.OfferBoard;
import com.delivery.common.dispatch.OfferSender;
import com.delivery.common.dispatch.OfferSnapshot;
import com.delivery.common.dispatch.OfferState;
import com.delivery.common.event.DispatchOffer;
import com.delivery.common.event.OrderCreated;
import com.delivery.dispatchengine.candidate.Candidate;
import com.delivery.dispatchengine.candidate.CandidateFinder;
import com.delivery.dispatchengine.config.DispatchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DispatchServiceTest {

    private static final long ORDER_ID = 881520076148810405L;
    private static final long RIDER_ID = 881520076849260058L;
    private static final long NOW = Instant.parse("2026-08-30T04:00:00Z").toEpochMilli();

    /**
     * 좀비 판정은 "지금" 을 기준으로 하는데 DispatchService 가 실제 시계를 본다.
     * 그래서 제안 시각은 고정값이 아니라 현재 시각에서 빼서 만들어야 한다.
     */
    private static long msAgo(long millis) {
        return System.currentTimeMillis() - millis;
    }

    @Mock private DispatchLease dispatchLease;
    @Mock private OfferBoard offerBoard;
    @Mock private CandidateList candidateList;
    @Mock private CandidateFinder candidateFinder;
    @Mock private OfferSender offerSender;
    @Mock private DispatchEventPublisher eventPublisher;

    private DispatchService dispatchService;

    private static final DispatchProperties PROPERTIES =
            new DispatchProperties(3000, 30, 10, 10, Duration.ofSeconds(30));

    private static OrderCreated order() {
        return new OrderCreated(ORDER_ID, "store-001",
                37.498095, 127.027610, 37.504198, 127.048985,
                "Z3749_12702", 18000, Instant.ofEpochMilli(NOW));
    }

    private static DispatchOffer offer() {
        return new DispatchOffer(1L, ORDER_ID, RIDER_ID, 1, Instant.ofEpochMilli(NOW));
    }

    @BeforeEach
    void setUp() {
        dispatchService = new DispatchService(dispatchLease, offerBoard, candidateList,
                candidateFinder, offerSender, eventPublisher, PROPERTIES);
        ReflectionTestUtils.setField(dispatchService, "instanceId", "dispatch-engine:8093");
        given(dispatchLease.acquire(anyLong(), anyString())).willAnswer(c -> c.getArgument(1));
        given(dispatchLease.release(anyLong(), anyString())).willReturn(true);
    }

    private void givenCandidatesFound() {
        given(candidateFinder.find(anyDouble(), anyDouble(), anyLong()))
                .willReturn(List.of(new Candidate(RIDER_ID, 1.2, 0, 1.2)));
    }

    @Test
    void sendsOfferToFirstCandidateForNewOrder() {
        given(offerBoard.read(ORDER_ID)).willReturn(null);
        givenCandidatesFound();
        given(offerSender.offerToNextCandidate(eq(ORDER_ID), anyInt())).willReturn(offer());

        dispatchService.dispatch(order());

        verify(offerSender).offerToNextCandidate(ORDER_ID, 1);
        verify(eventPublisher).publishDispatching(ORDER_ID);
    }

    /** 후보를 점수순 그대로 넘긴다. 순서가 뒤집히면 제일 가까운 라이더가 1순위가 아니게 된다 */
    @Test
    void savesCandidatesInScoreOrder() {
        given(offerBoard.read(ORDER_ID)).willReturn(null);
        given(candidateFinder.find(anyDouble(), anyDouble(), anyLong())).willReturn(List.of(
                new Candidate(RIDER_ID, 1.2, 0, 1.2),
                new Candidate(RIDER_ID + 1, 2.4, 0, 2.4)));
        given(offerSender.offerToNextCandidate(anyLong(), anyInt())).willReturn(offer());

        dispatchService.dispatch(order());

        verify(candidateList).replace(ORDER_ID, List.of(RIDER_ID, RIDER_ID + 1));
    }

    @Test
    void skipsWhenAnotherInstanceHoldsTheLease() {
        given(dispatchLease.acquire(anyLong(), anyString())).willReturn(null);

        dispatchService.dispatch(order());

        verify(candidateFinder, never()).find(anyDouble(), anyDouble(), anyLong());
        verify(offerSender, never()).offerToNextCandidate(anyLong(), anyInt());
    }

    // ── 좀비 판정 표 (기능 정의서 DE-01 규칙 2번) ──────────────────────────

    @Test
    void skipsAlreadyAcceptedOrder() {
        given(offerBoard.read(ORDER_ID))
                .willReturn(new OfferSnapshot(1L, RIDER_ID, OfferState.ACCEPTED, 1, NOW));

        dispatchService.dispatch(order());

        verify(candidateFinder, never()).find(anyDouble(), anyDouble(), anyLong());
    }

    @Test
    void skipsCancelledOrder() {
        given(offerBoard.read(ORDER_ID))
                .willReturn(new OfferSnapshot(1L, RIDER_ID, OfferState.CANCELLED, 1, NOW));

        dispatchService.dispatch(order());

        verify(candidateFinder, never()).find(anyDouble(), anyDouble(), anyLong());
    }

    /** 아직 10초 타이머가 살아 있는 제안이다. offer-relay 가 이어받으니 우리는 손 뗀다 */
    @Test
    void skipsOfferStillInFlight() {
        given(offerBoard.read(ORDER_ID))
                .willReturn(new OfferSnapshot(1L, RIDER_ID, OfferState.OFFERED, 1, msAgo(5_000)));

        dispatchService.dispatch(order());

        verify(candidateFinder, never()).find(anyDouble(), anyDouble(), anyLong());
    }

    /**
     * 제안이 10초짜리인데 30초가 지나도 OFFERED 라면 타이머 메시지가 애초에 없었다는 뜻이다.
     * EXISTS 만 보고 넘기면 이런 주문이 영영 방치된다.
     */
    @Test
    void reDispatchesZombieOfferStuckInOfferedState() {
        given(offerBoard.read(ORDER_ID))
                .willReturn(new OfferSnapshot(1L, RIDER_ID, OfferState.OFFERED, 1, msAgo(45_000)));
        givenCandidatesFound();
        given(offerSender.offerToNextCandidate(anyLong(), anyInt())).willReturn(offer());

        dispatchService.dispatch(order());

        verify(offerSender).offerToNextCandidate(ORDER_ID, 1);
    }

    @Test
    void reDispatchesWhenPreviousAttemptExpired() {
        given(offerBoard.read(ORDER_ID))
                .willReturn(new OfferSnapshot(1L, RIDER_ID, OfferState.EXPIRED, 3, msAgo(60_000)));
        givenCandidatesFound();
        given(offerSender.offerToNextCandidate(anyLong(), anyInt())).willReturn(offer());

        dispatchService.dispatch(order());

        verify(offerSender).offerToNextCandidate(ORDER_ID, 1);
    }

    // ── 후보가 없을 때 ────────────────────────────────────────────────────

    @Test
    void publishesDispatchFailedWhenNoRiderIsAvailable() {
        given(offerBoard.read(ORDER_ID)).willReturn(null);
        given(candidateFinder.find(anyDouble(), anyDouble(), anyLong())).willReturn(List.of());

        dispatchService.dispatch(order());

        verify(offerBoard).writeState(ORDER_ID, OfferState.FAILED);
        verify(eventPublisher).publishFailed(ORDER_ID, "NO_CANDIDATE");
        verify(offerSender, never()).offerToNextCandidate(anyLong(), anyInt());
    }

    /** 후보는 있었는데 그 사이 전부 다른 주문에 찜당한 경우 */
    @Test
    void publishesDispatchFailedWhenEveryCandidateWasTaken() {
        given(offerBoard.read(ORDER_ID)).willReturn(null);
        givenCandidatesFound();
        given(offerSender.offerToNextCandidate(anyLong(), anyInt())).willReturn(null);

        dispatchService.dispatch(order());

        verify(eventPublisher).publishFailed(ORDER_ID, "ALL_CANDIDATES_TAKEN");
        verify(eventPublisher, never()).publishDispatching(anyLong());
    }

    /** 리스를 잃은 채로 일했으면 반드시 로그로 남아야 한다. 제안이 두 개 나갔을 수 있다 */
    @Test
    void releasesLeaseEvenWhenDispatchFails() {
        given(offerBoard.read(ORDER_ID)).willReturn(null);
        given(candidateFinder.find(anyDouble(), anyDouble(), anyLong()))
                .willThrow(new IllegalStateException("레디스가 죽었다"));

        try {
            dispatchService.dispatch(order());
        } catch (IllegalStateException ignored) {
            // 예외는 위로 올라가야 컨슈머가 ack 하지 않는다
        }

        verify(dispatchLease).release(eq(ORDER_ID), anyString());
    }
}
