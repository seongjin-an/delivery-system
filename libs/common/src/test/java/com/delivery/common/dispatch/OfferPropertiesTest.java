package com.delivery.common.dispatch;

import com.delivery.common.autoconfigure.CommonDispatchAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 락 TTL 세 개를 서비스 yml 에서 빼고 여기 기본값에만 두기로 했다.
 * 그 기본값이 실제로 바인딩되는지 못박아둔다.
 *
 * <p>안 박아두면 조용히 깨진다. {@code @DefaultValue} 가 안 먹으면 Duration 이 null 로 들어오는데,
 * 기동은 멀쩡히 되고 첫 배차에서 NPE 가 난다. 그때는 "왜 락이 안 잡히지" 부터 뒤지게 된다.
 */
class OfferPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonDispatchAutoConfiguration.class));

    @Test
    void bindsDefaultsWhenNothingIsConfigured() {
        contextRunner.run(context -> {
            OfferProperties properties = context.getBean(OfferProperties.class);

            assertThat(properties.leaseTtl()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.riderLockTtl()).isEqualTo(Duration.ofSeconds(12));
            assertThat(properties.stateTtl()).isEqualTo(Duration.ofMinutes(10));
        });
    }

    /** 부하를 걸어보고 조정할 값이라 덮을 수 있어야 한다 */
    @Test
    void overridesDefaultsFromConfiguration() {
        contextRunner
                .withPropertyValues("delivery.offer.rider-lock-ttl=20s")
                .run(context -> {
                    OfferProperties properties = context.getBean(OfferProperties.class);

                    assertThat(properties.riderLockTtl()).isEqualTo(Duration.ofSeconds(20));
                    // 하나만 덮어도 나머지는 기본값이 살아 있어야 한다
                    assertThat(properties.leaseTtl()).isEqualTo(Duration.ofSeconds(15));
                });
    }

    /**
     * 라이더 찜(12초)은 제안 TTL(10초)보다 길어야 한다.
     * 먼저 풀리면 제안이 아직 살아 있는데 다른 주문이 그 라이더를 또 잡아간다.
     */
    @Test
    void riderLockOutlivesTheOffer() {
        contextRunner.run(context -> {
            OfferProperties properties = context.getBean(OfferProperties.class);

            assertThat(properties.riderLockTtl().toMillis())
                    .isGreaterThan(com.delivery.common.RabbitTopology.OFFER_TTL_MS);
        });
    }
}
