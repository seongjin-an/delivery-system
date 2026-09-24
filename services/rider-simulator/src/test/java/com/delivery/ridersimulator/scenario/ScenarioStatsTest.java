package com.delivery.ridersimulator.scenario;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioStatsTest {

    @Test
    void showsTheLastFullSecond() {
        ScenarioStats stats = new ScenarioStats();
        stats.locationSent(10_000);
        stats.locationSent(10_400);
        stats.locationSent(10_900);
        stats.locationSent(11_100);

        // 11초 칸에 있을 때는 10초 칸(3건)을 보여준다. 지금 칸은 아직 차는 중이라 믿을 수 없다
        assertThat(stats.locationsPerSec(11_500)).isEqualTo(3);
    }

    @Test
    void dropsToZeroWhenNothingWasSentForASecond() {
        // 멈춘 뒤에도 마지막 값이 계속 보이면 "아직 돌고 있네" 로 착각한다
        ScenarioStats stats = new ScenarioStats();
        stats.locationSent(10_000);
        stats.locationSent(10_500);

        assertThat(stats.locationsPerSec(12_200)).isZero();
    }
}
