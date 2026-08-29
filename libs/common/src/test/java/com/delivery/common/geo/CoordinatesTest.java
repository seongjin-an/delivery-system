package com.delivery.common.geo;

import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoordinatesTest {

    @ParameterizedTest
    @CsvSource({
            "37.5665, 126.9780",   // 서울시청
            "35.1796, 129.0756",   // 부산
            "33.0, 124.0",         // 남서쪽 경계 (포함)
            "39.0, 132.0"          // 북동쪽 경계 (포함)
    })
    void acceptsCoordinateInsideKorea(double lat, double lng) {
        assertThat(Coordinates.isValid(lat, lng)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "32.999, 126.978",     // 위도가 한 뼘 아래
            "39.001, 126.978",
            "37.566, 123.999",
            "37.566, 132.001",
            "126.978, 37.566"      // 위경도를 바꿔 넣은 흔한 실수
    })
    void rejectsCoordinateOutsideKorea(double lat, double lng) {
        assertThat(Coordinates.isValid(lat, lng)).isFalse();
    }

    /** NaN 은 어떤 비교를 해도 false 라서 범위 검사에 그냥 걸린다. 그게 의도대로인지 못 박아둔다 */
    @Test
    void rejectsNaN() {
        assertThat(Coordinates.isValid(Double.NaN, 126.978)).isFalse();
        assertThat(Coordinates.isValid(37.566, Double.NaN)).isFalse();
    }

    @Test
    void validateThrowsWithInvalidCoordinateCode() {
        assertThatThrownBy(() -> Coordinates.validate(0.0, 0.0))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_COORDINATE);
    }

    @Test
    void validatePassesSilentlyForValidCoordinate() {
        Coordinates.validate(37.5665, 126.9780);
    }

    /**
     * 서울 위도에서 경도 0.0002도는 대략 17.6m 다.
     * location-ingest 의 15m 이동 필터가 이 계산에 기대는 거라 값이 크게 흔들리면 안 된다.
     */
    @Test
    void measuresShortDistanceAccurately() {
        double meters = Coordinates.distanceMeters(37.5665, 126.9780, 37.5665, 126.9782);
        assertThat(meters).isBetween(17.0, 18.5);
    }

    @Test
    void measuresZeroForSamePoint() {
        assertThat(Coordinates.distanceMeters(37.5665, 126.9780, 37.5665, 126.9780)).isZero();
    }
}
