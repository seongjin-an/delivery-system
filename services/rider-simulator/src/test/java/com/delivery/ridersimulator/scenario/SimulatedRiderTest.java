package com.delivery.ridersimulator.scenario;

import com.delivery.common.geo.Coordinates;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SimulatedRiderTest {

    private static final double LAT = 37.5665;
    private static final double LNG = 126.9780;

    @Test
    void eachStepIsTheRequestedLength() {
        // 20km/h 로 3초면 16.7m. 이동거리 필터(15m)보다 짧아지면 위치 트래픽이 keepalive 로만 나간다
        SimulatedRider rider = new SimulatedRider(1L, LAT, LNG, 8);
        double beforeLat = rider.lat();
        double beforeLng = rider.lng();

        rider.walk(16.7);

        assertThat(Coordinates.distanceMeters(beforeLat, beforeLng, rider.lat(), rider.lng()))
                .isCloseTo(16.7, within(0.2));
    }

    @RepeatedTest(20)
    void staysInsideTheAreaAfterLongWalk() {
        SimulatedRider rider = new SimulatedRider(1L, LAT, LNG, 1);
        for (int i = 0; i < 2_000; i++) {
            rider.walk(16.7);
        }
        // 원 밖으로 나가면 다음 걸음에 가운데로 돌아서니 넘어도 한 걸음까지다
        assertThat(Coordinates.distanceMeters(LAT, LNG, rider.lat(), rider.lng())).isLessThan(1_000 + 16.7 + 1);
    }

    @Test
    void startsInsideTheArea() {
        for (int i = 0; i < 1_000; i++) {
            SimulatedRider rider = new SimulatedRider(i, LAT, LNG, 8);
            assertThat(Coordinates.distanceMeters(LAT, LNG, rider.lat(), rider.lng())).isLessThanOrEqualTo(8_000.5);
        }
    }
}
