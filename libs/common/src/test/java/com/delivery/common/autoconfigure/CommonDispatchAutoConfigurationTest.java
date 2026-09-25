package com.delivery.common.autoconfigure;

import com.delivery.common.dispatch.DispatchEventPublisher;
import com.delivery.common.dispatch.OfferBoard;
import com.delivery.common.dispatch.OfferChannel;
import com.delivery.common.dispatch.OfferSender;
import com.delivery.common.dispatch.RiderState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배차 공용 부품이 <b>필요한 서비스에서만</b> 켜지는지 본다.
 *
 * <p>여덟 개 서비스가 쓰는 게 제각각이다. location-ingest 는 카프카만 쓰고 레디스를 안 쓰고,
 * rider-simulator 는 셋 다 안 쓴다. 자동설정 하나가 그걸 다 받아줘야 한다.
 *
 * <p>실제로 여기서 한 번 터졌다. 안쪽 클래스에 {@code @ConditionalOnClass} 를 걸어놨는데도
 * location-ingest 가 {@code NoClassDefFoundError: ...RedisScript} 로 기동에 실패했다.
 * 바깥 자동설정 클래스가 Lua 스크립트를 static 필드로 들고 있었기 때문이다 —
 * 조건을 따지기 전에 클래스를 불러오다 죽는다. 단위 테스트로는 절대 안 잡히고,
 * 띄워봐야만 보인다. 그래서 띄우는 대신 클래스패스를 가려서 여기서 잡는다.
 */
class CommonDispatchAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RedisAutoConfiguration.class, CommonDispatchAutoConfiguration.class));

    /** location-ingest 처럼 레디스를 아예 안 쓰는 서비스 */
    @Test
    void startsWithoutRedisOnTheClasspath() {
        contextRunner
                .withClassLoader(new FilteredClassLoader("org.springframework.data.redis"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OfferBoard.class);
                    assertThat(context).doesNotHaveBean(RiderState.class);
                });
    }

    /** geo-indexer 처럼 레디스는 쓰는데 래빗엠큐는 안 쓰는 서비스 */
    @Test
    void skipsOfferSenderWithoutRabbit() {
        contextRunner
                .withClassLoader(new FilteredClassLoader("org.springframework.amqp"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OfferBoard.class);
                    assertThat(context).doesNotHaveBean(OfferChannel.class);
                });
    }

    /** 카프카가 없으면 배차 결과를 발행할 일도 없다 */
    @Test
    void skipsEventPublisherWithoutKafka() {
        contextRunner
                .withClassLoader(new FilteredClassLoader("org.springframework.kafka"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DispatchEventPublisher.class);
                });
    }

    /** rider-simulator 처럼 셋 다 안 쓰는 서비스 */
    @Test
    void startsWithNoneOfThem() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(
                        "org.springframework.data.redis",
                        "org.springframework.amqp",
                        "org.springframework.kafka"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * dispatch-engine 처럼 셋 다 쓰는 서비스.
     *
     * <p>래빗엠큐 자동설정을 따로 얹는다. OfferSender 는 RabbitTemplate <b>빈</b>이 있어야 켜지는데,
     * 클래스만 있고 빈이 없으면 조건에서 떨어진다. (빈을 만드는 것과 브로커에 붙는 건 다른 일이라
     * 래빗엠큐가 안 떠 있어도 여기서는 문제가 없다)
     */
    @Test
    void providesEveryPartWhenEverythingIsPresent() {
        contextRunner.withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
                .run(context -> {
            assertThat(context).hasSingleBean(OfferBoard.class);
            assertThat(context).hasSingleBean(RiderState.class);
            assertThat(context).hasSingleBean(OfferSender.class);
                });
    }
}
