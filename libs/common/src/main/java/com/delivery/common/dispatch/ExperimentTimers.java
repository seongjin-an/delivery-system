package com.delivery.common.dispatch;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * 2단계 실험: 레디스 판과 MySQL 판을 같은 자리에서 재는 타이머.
 *
 * <p>고정 구간(1ms ~ 5s)을 달아둔다. 판 전후로 구간별 누적 개수를 긁어서 빼면 재시작 없이 그 판만의 분포가 나온다.
 * 지표를 비우려고 재시작하면 JVM 이 식은 채로 시작해서 첫 30초가 느리게 나온다(제안 타이머 실험에서 겪었다).
 */
public final class ExperimentTimers {

    public static Timer slo(MeterRegistry registry, String name) {
        return Timer.builder(name)
                .serviceLevelObjectives(ms(1), ms(2), ms(5), ms(10), ms(20), ms(50), ms(100),
                        ms(200), ms(500), ms(1000), ms(2000), ms(5000))
                .register(registry);
    }

    private static Duration ms(long millis) {
        return Duration.ofMillis(millis);
    }

    private ExperimentTimers() {
    }
}
