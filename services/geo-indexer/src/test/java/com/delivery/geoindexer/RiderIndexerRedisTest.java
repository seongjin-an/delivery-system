package com.delivery.geoindexer;

import com.delivery.common.Ids;
import com.delivery.common.RedisKeys;
import com.delivery.common.event.RiderLocation;
import com.delivery.common.rider.RiderStateFields;
import com.delivery.common.rider.RiderStatus;
import com.delivery.geoindexer.config.RedisScriptConfig;
import com.delivery.geoindexer.index.IndexResult;
import com.delivery.geoindexer.index.RiderIndexer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * GI-01 인덱싱을 진짜 레디스에 돌려본다.
 *
 * <p>여기서 확인하고 싶은 건 규칙 4번 하나다 — <b>배달 중인 라이더의 상태를 덮어쓰지 않는가.</b>
 * 이게 깨지면 증상이 "배달 중인 사람에게 새 콜이 간다" 로 나타나는데, 목으로는 "내가 짠 목이
 * 내 기대대로 동작한다" 만 확인하게 된다. 조건 분기가 실제 레디스에서 어떻게 도는지는
 * 레디스가 직접 답해줘야 안다.
 *
 * <p>레디스가 없으면 통째로 건너뛴다. {@code ./scripts/start.sh} 로 인프라를 띄우면 돈다.
 */
class RiderIndexerRedisTest {

    private static final String HOST = "localhost";
    private static final int PORT = 6380;

    /** 강남역 근처. 한국 범위 안이라 좌표 검증에 안 걸린다 */
    private static final double LAT = 37.498095;
    private static final double LNG = 127.027610;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
            .withUserConfiguration(RedisScriptConfig.class)
            .withBean(RiderIndexer.class)
            .withPropertyValues(
                    "spring.data.redis.host=" + HOST,
                    "spring.data.redis.port=" + PORT);

    private long riderId;

    @BeforeAll
    static void requireRedis() {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(HOST, PORT), 300);
        } catch (IOException e) {
            assumeTrue(false, "레디스(" + HOST + ":" + PORT + ")가 없어서 건너뛴다. "
                    + "./scripts/start.sh 로 띄우면 돈다");
        }
    }

    @BeforeEach
    void freshRider() {
        // 개발용 레디스를 같이 쓰기 때문에 매번 새 아이디로 논다.
        riderId = Ids.newId();
    }

    @AfterEach
    void cleanUp() {
        run((indexer, redis) -> {
            redis.delete(RedisKeys.riderState(riderId));
            redis.opsForZSet().remove(RedisKeys.RIDERS_GEO, Long.toString(riderId));
            redis.opsForZSet().remove(RedisKeys.RIDERS_HEARTBEAT, Long.toString(riderId));
        });
    }

    private void run(BiConsumer<RiderIndexer, StringRedisTemplate> body) {
        contextRunner.run((AssertableApplicationContext context) -> body.accept(
                context.getBean(RiderIndexer.class),
                context.getBean(StringRedisTemplate.class)));
    }

    private static RiderLocation at(long riderId, double lat, double lng) {
        return new RiderLocation(riderId, lat, lng, "Z3749_12702", Instant.now());
    }

    private static String status(StringRedisTemplate redis, long riderId) {
        return (String) redis.opsForHash().get(RedisKeys.riderState(riderId), RiderStateFields.STATUS);
    }

    private void givenStatus(StringRedisTemplate redis, RiderStatus status) {
        redis.opsForHash().put(
                RedisKeys.riderState(riderId), RiderStateFields.STATUS, status.name());
    }

    // ── 세 키를 다 채우는가 ───────────────────────────────────────────────

    @Test
    void indexesNewRiderIntoGeoStateAndHeartbeat() {
        run((indexer, redis) -> {
            IndexResult result = indexer.index(List.of(at(riderId, LAT, LNG)));

            assertThat(result.indexed()).isEqualTo(1);
            assertThat(result.cameOnline()).isEqualTo(1);

            // GEO — GEOSEARCH 로 잡혀야 후보가 된다
            assertThat(redis.opsForGeo().position(RedisKeys.RIDERS_GEO, Long.toString(riderId)))
                    .isNotEmpty();
            // 시각 인덱스 — GI-02 오프라인 정리가 이걸 훑는다
            assertThat(redis.opsForZSet().score(RedisKeys.RIDERS_HEARTBEAT, Long.toString(riderId)))
                    .isNotNull();

            Map<Object, Object> state = redis.opsForHash().entries(RedisKeys.riderState(riderId));
            assertThat(state.get(RiderStateFields.STATUS)).isEqualTo(RiderStatus.IDLE.name());
            assertThat(state.get(RiderStateFields.LAST_SEEN_AT)).isNotNull();
            // 대기 보너스가 이걸 쓴다. 빼먹어도 에러가 안 나서 더 눈에 안 띈다
            assertThat(state.get(RiderStateFields.IDLE_SINCE)).isNotNull();
        });
    }

    // ── 규칙 4번: status 는 조건부로만 ────────────────────────────────────

    @Test
    void bringsOfflineRiderBackOnline() {
        run((indexer, redis) -> {
            givenStatus(redis, RiderStatus.OFFLINE);

            indexer.index(List.of(at(riderId, LAT, LNG)));

            assertThat(status(redis, riderId)).isEqualTo(RiderStatus.IDLE.name());
        });
    }

    /**
     * 배달 중인 라이더를 IDLE 로 덮으면 새 주문 후보로 잡힌다.
     * 손님 둘이 같은 라이더를 기다리게 된다.
     */
    @Test
    void neverTouchesDeliveringRider() {
        run((indexer, redis) -> {
            givenStatus(redis, RiderStatus.DELIVERING);

            IndexResult result = indexer.index(List.of(at(riderId, LAT, LNG)));

            assertThat(status(redis, riderId)).isEqualTo(RiderStatus.DELIVERING.name());
            assertThat(result.cameOnline()).isZero();
        });
    }

    /** 제안을 들고 있는 라이더도 마찬가지다. 수락하기도 전에 다른 주문이 채간다 */
    @Test
    void neverTouchesRiderHoldingAnOffer() {
        run((indexer, redis) -> {
            givenStatus(redis, RiderStatus.OFFERED);

            indexer.index(List.of(at(riderId, LAT, LNG)));

            assertThat(status(redis, riderId)).isEqualTo(RiderStatus.OFFERED.name());
        });
    }

    /**
     * IDLE 이면 그대로 둔다. idleSince 를 다시 찍으면 안 된다 —
     * 좌표가 3초마다 오니까 대기 시간이 영영 0으로 리셋돼서 대기 보너스가 죽는다.
     */
    @Test
    void keepsIdleSinceWhenRiderIsAlreadyIdle() {
        run((indexer, redis) -> {
            indexer.index(List.of(at(riderId, LAT, LNG)));
            Object first = redis.opsForHash()
                    .get(RedisKeys.riderState(riderId), RiderStateFields.IDLE_SINCE);

            indexer.index(List.of(at(riderId, LAT + 0.001, LNG + 0.001)));
            Object second = redis.opsForHash()
                    .get(RedisKeys.riderState(riderId), RiderStateFields.IDLE_SINCE);

            assertThat(second).isEqualTo(first);
        });
    }

    /** 상태는 안 건드려도 좌표는 갱신돼야 한다. 배달 중인 라이더의 위치도 계속 따라가야 한다 */
    @Test
    void stillUpdatesCoordinatesOfDeliveringRider() {
        run((indexer, redis) -> {
            givenStatus(redis, RiderStatus.DELIVERING);

            indexer.index(List.of(at(riderId, 37.5, 127.03)));

            Map<Object, Object> state = redis.opsForHash().entries(RedisKeys.riderState(riderId));
            assertThat(state.get(RiderStateFields.LAT)).isEqualTo("37.5");
            assertThat(state.get(RiderStateFields.LNG)).isEqualTo("127.03");
        });
    }

    // ── 배치 안에서 마지막 것만 남기는가 ──────────────────────────────────

    @Test
    void keepsOnlyTheLastPositionPerRiderInABatch() {
        run((indexer, redis) -> {
            IndexResult result = indexer.index(List.of(
                    at(riderId, 37.49, 127.02),
                    at(riderId, 37.50, 127.03),
                    at(riderId, 37.51, 127.04)));

            assertThat(result.received()).isEqualTo(3);
            assertThat(result.indexed()).isEqualTo(1);
            assertThat(result.deduped()).isEqualTo(2);

            Map<Object, Object> state = redis.opsForHash().entries(RedisKeys.riderState(riderId));
            assertThat(state.get(RiderStateFields.LAT)).isEqualTo("37.51");
        });
    }

    @Test
    void indexesEveryRiderInABatch() {
        long other = Ids.newId();
        try {
            run((indexer, redis) -> {
                IndexResult result = indexer.index(List.of(
                        at(riderId, LAT, LNG),
                        at(other, LAT + 0.01, LNG + 0.01)));

                assertThat(result.indexed()).isEqualTo(2);
                assertThat(redis.opsForZSet().score(RedisKeys.RIDERS_GEO, Long.toString(other)))
                        .isNotNull();
            });
        } finally {
            run((indexer, redis) -> {
                redis.delete(RedisKeys.riderState(other));
                redis.opsForZSet().remove(RedisKeys.RIDERS_GEO, Long.toString(other));
                redis.opsForZSet().remove(RedisKeys.RIDERS_HEARTBEAT, Long.toString(other));
            });
        }
    }

    @Test
    void doesNothingForAnEmptyBatch() {
        run((indexer, redis) -> assertThat(indexer.index(List.of()).indexed()).isZero());
    }
}
