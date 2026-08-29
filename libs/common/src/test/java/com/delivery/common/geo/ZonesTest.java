package com.delivery.common.geo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ZonesTest {

    /** 기능 정의서 3.4 에 적힌 예시 그대로 */
    @Test
    void matchesSpecExample() {
        assertThat(Zones.of(37.5665, 126.9780)).isEqualTo("Z3756_12697");
    }

    /**
     * 37.57 * 100 이 double 로는 3756.9999... 가 된다.
     * 그냥 floor 하면 3756 이 나와서 격자가 한 칸씩 밀린다 — 그래서 epsilon 을 더해뒀고, 이 테스트가 그 감시자다.
     */
    @Test
    void doesNotDriftOnGridBoundary() {
        assertThat(Zones.of(37.57, 126.97)).isEqualTo("Z3757_12697");
        assertThat(Zones.of(37.58, 126.98)).isEqualTo("Z3758_12698");
    }

    /** 한 칸(0.01도) 안의 좌표는 전부 같은 존이어야 후보 검색이나 락 샤딩이 말이 된다 */
    @ParameterizedTest
    @CsvSource({
            "37.5600, 126.9700",
            "37.5650, 126.9750",
            "37.5699, 126.9799"
    })
    void foldsEveryPointInOneCellIntoSameZone(double lat, double lng) {
        assertThat(Zones.of(lat, lng)).isEqualTo("Z3756_12697");
    }

    @Test
    void separatesAdjacentCells() {
        assertThat(Zones.of(37.5699, 126.9780)).isNotEqualTo(Zones.of(37.5701, 126.9780));
    }
}
