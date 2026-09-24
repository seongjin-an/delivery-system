package com.delivery.settlementservice.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class FeePolicyTest {

    @ParameterizedTest(name = "{0}m → {1}원")
    @CsvSource({
            "0, 3000",
            "1000, 3000",     // 1km 까지는 기본료만
            "1001, 3500",     // 1m 라도 넘으면 한 칸
            "1500, 3500",
            "1501, 4000",
            "2300, 4500",     // 넘은 1.3km 는 올림해서 3칸
            "20000, 22000"    // 주문 거리 상한(20km)
    })
    void chargesPerStartedHalfKilometerAfterFirstKilometer(int meters, int expectedKrw) {
        assertThat(FeePolicy.feeKrw(meters)).isEqualTo(expectedKrw);
    }
}
