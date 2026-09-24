package com.delivery.geoindexer;

import com.delivery.common.Ids;
import com.delivery.common.RedisKeys;
import com.delivery.common.rider.RiderStateFields;
import com.delivery.common.rider.RiderStatus;
import com.delivery.geoindexer.config.SweepProperties;
import com.delivery.geoindexer.geo.RiderStateReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * GI-03 이 blockers 를 제대로 가려내는지 진짜 레디스로 본다. 개발용 레디스를 쓰고, 라이더 아이디를 매번 새로 뽑는다.
 */
class RiderStateReaderRedisTest {

    private static final double LAT = 37.498095;
    private static final double LNG = 127.027610;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
            .withPropertyValues("spring.data.redis.host=localhost", "spring.data.redis.port=6380");

    private long riderId;

    @BeforeAll
    static void requireRedis() {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("localhost", 6380), 300);
        } catch (IOException e) {
            assumeTrue(false, "레디스(localhost:6380)가 없어서 건너뛴다. ./scripts/start.sh 로 띄우면 돈다");
        }
    }

    @BeforeEach
    void freshRider() {
        riderId = Ids.newId();
    }

    @AfterEach
    void cleanUp() {
        run((reader, redis) -> {
            redis.delete(List.of(RedisKeys.riderState(riderId), RedisKeys.riderLock(riderId)));
            redis.opsForZSet().remove(RedisKeys.RIDERS_GEO, Long.toString(riderId));
            redis.opsForZSet().remove(RedisKeys.RIDERS_HEARTBEAT, Long.toString(riderId));
        });
    }

    @Test
    void idleFreshRiderInGeoIsACandidate() {
        run((reader, redis) -> {
            given(redis, RiderStatus.IDLE, Duration.ofSeconds(2));

            RiderStateReader.RiderSnapshot snapshot = reader.read(riderId);

            assertThat(snapshot.candidate()).isTrue();
            assertThat(snapshot.blockers()).isEmpty();
            assertThat(snapshot.position().lat()).isCloseTo(LAT, within(1e-5));
            assertThat(snapshot.state()).containsEntry(RiderStateFields.STATUS, "IDLE");
        });
    }

    @Test
    void offeredAndLockedRiderShowsBothReasons() {
        run((reader, redis) -> {
            given(redis, RiderStatus.OFFERED, Duration.ofSeconds(2));
            redis.opsForValue().set(RedisKeys.riderLock(riderId), "558668931353510983", Duration.ofSeconds(12));

            RiderStateReader.RiderSnapshot snapshot = reader.read(riderId);

            assertThat(snapshot.candidate()).isFalse();
            assertThat(snapshot.lockedByOrder()).isEqualTo("558668931353510983");
            assertThat(snapshot.blockers()).hasSize(2)
                    .anyMatch(b -> b.contains("OFFERED"))
                    .anyMatch(b -> b.contains("558668931353510983"));
        });
    }

    @Test
    void unknownRiderIsExplainedNotErrored() {
        run((reader, redis) -> {
            RiderStateReader.RiderSnapshot snapshot = reader.read(riderId);

            assertThat(snapshot.candidate()).isFalse();
            assertThat(snapshot.position()).isNull();
            assertThat(snapshot.heartbeatAgoMs()).isNull();
            assertThat(snapshot.blockers()).anyMatch(b -> b.contains("rider:state 가 없다"));
        });
    }

    @Test
    void staleHeartbeatWarnsBeforeSweepRemovesIt() {
        run((reader, redis) -> {
            given(redis, RiderStatus.IDLE, Duration.ofSeconds(45));

            RiderStateReader.RiderSnapshot snapshot = reader.read(riderId);

            assertThat(snapshot.candidate()).isFalse();
            assertThat(snapshot.blockers()).singleElement().asString().contains("오프라인 정리");
        });
    }

    private void given(StringRedisTemplate redis, RiderStatus status, Duration quietFor) {
        String member = Long.toString(riderId);
        redis.opsForGeo().add(RedisKeys.RIDERS_GEO, new Point(LNG, LAT), member);
        redis.opsForZSet().add(RedisKeys.RIDERS_HEARTBEAT, member, System.currentTimeMillis() - quietFor.toMillis());
        redis.opsForHash().putAll(RedisKeys.riderState(riderId), Map.of(RiderStateFields.STATUS, status.name()));
    }

    private void run(BiConsumer<RiderStateReader, StringRedisTemplate> body) {
        contextRunner.run(context -> {
            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
            SweepProperties sweep = new SweepProperties(Duration.ofSeconds(30), Duration.ofSeconds(8), 500, Duration.ofSeconds(6));
            body.accept(new RiderStateReader(redis, sweep), redis);
        });
    }
}
