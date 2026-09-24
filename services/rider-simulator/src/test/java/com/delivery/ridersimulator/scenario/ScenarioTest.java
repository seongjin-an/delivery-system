package com.delivery.ridersimulator.scenario;

import com.delivery.common.event.OfferPush;
import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import com.delivery.ridersimulator.config.SimulatorProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 제안을 받았을 때 무엇을 부르는지 본다. 바깥 API 는 목이다. 전체 흐름은 서비스를 다 띄워서 확인한다.
 *
 * <p>지연을 전부 0 으로 두고, 좌표 주기는 길게 둬서 라이더 루프가 테스트를 방해하지 않게 한다.
 */
class ScenarioTest {

    private static final long OFFER_ID = 890391265973758795L;
    private static final long ORDER_ID = 890391260219157193L;

    private final SimulatorClient client = mock(SimulatorClient.class);
    private final SimulatorProperties defaults = new SimulatorProperties(
            "http://ingest", "http://order", "http://dispatch",
            3, 60_000, 0, 0.6, 0.2, 0, 0, 0,
            37.5665, 126.9780, 1, 20, 0);
    private final Scenario scenario = new Scenario(client, defaults);

    @BeforeEach
    void everyCallSucceeds() {
        given(client.accept(anyLong(), anyLong())).willReturn(SimulatorClient.OK);
        given(client.reject(anyLong(), anyLong())).willReturn(SimulatorClient.OK);
        given(client.pickUp(anyLong(), anyLong())).willReturn(SimulatorClient.OK);
        given(client.complete(anyLong(), anyLong())).willReturn(SimulatorClient.OK);
        given(client.sendLocation(any())).willReturn(SimulatorClient.OK);
        given(client.createOrder(anyDouble(), anyDouble(), anyDouble(), anyDouble())).willReturn(SimulatorClient.OK);
    }

    @AfterEach
    void stop() {
        scenario.stop();
    }

    @Test
    void acceptedOfferGoesOnToPickUpAndComplete() {
        long rider = startWith(1.0, 0.0);

        scenario.onOffer(push(rider));

        InOrder order = inOrder(client);
        order.verify(client, timeout(2_000)).accept(OFFER_ID, rider);
        order.verify(client, timeout(2_000)).pickUp(ORDER_ID, rider);
        order.verify(client, timeout(2_000)).complete(ORDER_ID, rider);
        verify(client, never()).reject(anyLong(), anyLong());
    }

    @Test
    void rejectedOfferOnlyRejects() {
        long rider = startWith(0.0, 1.0);

        scenario.onOffer(push(rider));

        verify(client, timeout(2_000)).reject(OFFER_ID, rider);
        verify(client, never()).accept(anyLong(), anyLong());
    }

    @Test
    void ignoredOfferCallsNothing() throws Exception {
        long rider = startWith(0.0, 0.0);

        scenario.onOffer(push(rider));
        Thread.sleep(200);

        verify(client, never()).accept(anyLong(), anyLong());
        verify(client, never()).reject(anyLong(), anyLong());
        assertThat(scenario.status().ignored()).isEqualTo(1);
    }

    @Test
    void failedAcceptStopsTheChainAndIsCountedByCode() {
        long rider = startWith(1.0, 0.0);
        given(client.accept(anyLong(), anyLong())).willReturn("OFFER_EXPIRED");

        scenario.onOffer(push(rider));

        verify(client, timeout(2_000)).accept(OFFER_ID, rider);
        verify(client, never()).pickUp(anyLong(), anyLong());
        awaitFailure("accept:OFFER_EXPIRED");
    }

    @Test
    void offerForRiderOutsideThisRunIsCountedSeparately() {
        startWith(1.0, 0.0);

        scenario.onOffer(push(42L));

        assertThat(scenario.status().unknownRider()).isEqualTo(1);
        assertThat(scenario.status().offersReceived()).isZero();
    }

    @Test
    void cannotStartTwice() {
        startWith(0.6, 0.2);

        assertThatThrownBy(() -> scenario.start(settings(0.6, 0.2)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INVALID_STATE);
    }

    @Test
    void ratesAddingUpOverOneAreRejected() {
        assertThatThrownBy(() -> scenario.start(settings(0.7, 0.4)))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
    }

    @Test
    void stopKeepsDeliveringWhatWasAlreadyAccepted() {
        // 수락한 배달까지 끊으면 라이더가 DELIVERING 으로 영영 남는다
        long rider = startWith(1.0, 0.0);
        scenario.onOffer(push(rider));
        scenario.stop();

        verify(client, timeout(2_000)).complete(ORDER_ID, rider);
        assertThat(scenario.status().running()).isFalse();
    }

    @Test
    void offersArrivingAfterStopAreIgnored() throws Exception {
        long rider = startWith(1.0, 0.0);
        scenario.stop();

        scenario.onOffer(push(rider));
        Thread.sleep(200);

        verify(client, never()).accept(anyLong(), anyLong());
        assertThat(scenario.status().ignored()).isEqualTo(1);
    }

    private long startWith(double acceptRate, double rejectRate) {
        scenario.start(settings(acceptRate, rejectRate));
        return scenario.riderIds().iterator().next();
    }

    private static ScenarioSettings settings(double acceptRate, double rejectRate) {
        return new ScenarioSettings(null, null, null, acceptRate, rejectRate,
                null, null, null, null, null, null, null, null);
    }

    private static OfferPush push(long riderId) {
        return new OfferPush(riderId, OFFER_ID, ORDER_ID, null, 9);
    }

    private void awaitFailure(String key) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline && !scenario.status().failures().containsKey(key)) {
            Thread.onSpinWait();
        }
        assertThat(scenario.status().failures()).containsEntry(key, 1L);
    }
}
