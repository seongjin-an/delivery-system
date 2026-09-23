package com.delivery.geoindexer;

import com.delivery.common.Ids;
import com.delivery.common.RedisKeys;
import com.delivery.common.rider.RiderStateFields;
import com.delivery.common.rider.RiderStatus;
import com.delivery.geoindexer.config.RedisScriptConfig;
import com.delivery.geoindexer.config.SweepProperties;
import com.delivery.geoindexer.sweep.OfflineSweeper;
import com.delivery.geoindexer.sweep.SweepOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * GI-02 오프라인 정리를 진짜 레디스에 돌려본다.
 *
 * <p>개발용 레디스를 같이 쓴다. 그래서 스위퍼가 이 테스트 라이더 말고 레디스에 남아 있던 다른
 * 조용한 라이더까지 같이 정리한다. 실제로 서비스가 하는 일과 같으니 그대로 두고, 확인은 이 테스트가
 * 만든 라이더만 본다.
 *
 * <p>레디스가 없으면 통째로 건너뛴다. {@code ./scripts/start.sh} 로 인프라를 띄우면 돈다.
 */
class OfflineSweeperRedisTest {

    private static final String HOST = "localhost";
    private static final int PORT = 6380;

    private static final double LAT = 37.498095;
    private static final double LNG = 127.027610;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
            .withUserConfiguration(RedisScriptConfig.class)
            .withPropertyValues(
                    "spring.data.redis.host=" + HOST,
                    "spring.data.redis.port=" + PORT);

    private final List<Long> riders = new ArrayList<>();

    @BeforeAll
    static void requireRedis() {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(HOST, PORT), 300);
        } catch (IOException e) {
            assumeTrue(false, "레디스(" + HOST + ":" + PORT + ")가 없어서 건너뛴다. "
                    + "./scripts/start.sh 로 띄우면 돈다");
        }
    }

    @AfterEach
    void cleanUp() {
        run((sweeper, redis) -> {
            for (long riderId : riders) {
                redis.delete(RedisKeys.riderState(riderId));
                redis.opsForZSet().remove(RedisKeys.RIDERS_GEO, Long.toString(riderId));
                redis.opsForZSet().remove(RedisKeys.RIDERS_HEARTBEAT, Long.toString(riderId));
            }
            redis.delete(RedisKeys.SWEEP_OFFLINE_LOCK);
        });
    }

    // ── 규칙 2번: status 에 따라 다르게 ─────────────────────────────────────

    @Test
    void quietIdleRiderGoesOffline() {
        run((sweeper, redis) -> {
            long rider = givenRider(redis, RiderStatus.IDLE, Duration.ofSeconds(31));

            sweepOnce(sweeper, redis);

            assertThat(inGeo(redis, rider)).isFalse();
            // heartbeat 에서도 빠져야 다음 주기에 또 안 뽑힌다
            assertThat(inHeartbeat(redis, rider)).isFalse();
            assertThat(status(redis, rider)).isEqualTo(RiderStatus.OFFLINE.name());
        });
    }

    @Test
    void riderSeenWithin30SecondsIsLeftAlone() {
        run((sweeper, redis) -> {
            long rider = givenRider(redis, RiderStatus.IDLE, Duration.ofSeconds(20));

            sweepOnce(sweeper, redis);

            assertThat(inGeo(redis, rider)).isTrue();
            assertThat(status(redis, rider)).isEqualTo(RiderStatus.IDLE.name());
        });
    }

    @Test
    void offeredRiderIsOnlyRemovedFromGeo() {
        run((sweeper, redis) -> {
            long rider = givenRider(redis, RiderStatus.OFFERED, Duration.ofSeconds(31));

            sweepOnce(sweeper, redis);

            assertThat(inGeo(redis, rider)).isFalse();
            // 제안이 만료돼 IDLE 이 되면 다음 주기에 다시 잡혀야 한다
            assertThat(inHeartbeat(redis, rider)).isTrue();
            assertThat(status(redis, rider)).isEqualTo(RiderStatus.OFFERED.name());
        });
    }

    @Test
    void deliveringRiderIsNotTouchedAtAll() {
        run((sweeper, redis) -> {
            long rider = givenRider(redis, RiderStatus.DELIVERING, Duration.ofMinutes(5));

            sweepOnce(sweeper, redis);

            assertThat(inGeo(redis, rider)).isTrue();
            assertThat(inHeartbeat(redis, rider)).isTrue();
            assertThat(status(redis, rider)).isEqualTo(RiderStatus.DELIVERING.name());
        });
    }

    @Test
    void offeredRiderIsSweptOnNextRoundOnceBackToIdle() {
        run((sweeper, redis) -> {
            long rider = givenRider(redis, RiderStatus.OFFERED, Duration.ofSeconds(31));
            sweepOnce(sweeper, redis);

            // RE-02 가 제안을 만료시키고 IDLE 로 돌려놓았다고 치자
            redis.opsForHash().put(RedisKeys.riderState(rider), RiderStateFields.STATUS, RiderStatus.IDLE.name());
            sweepOnce(sweeper, redis);

            assertThat(inHeartbeat(redis, rider)).isFalse();
            assertThat(status(redis, rider)).isEqualTo(RiderStatus.OFFLINE.name());
        });
    }

    @Test
    void unknownStatusIsNotOverwritten() {
        run((sweeper, redis) -> {
            long rider = givenRider(redis, null, Duration.ofSeconds(31));
            redis.opsForHash().put(RedisKeys.riderState(rider), RiderStateFields.STATUS, "ON_BREAK");

            sweepOnce(sweeper, redis);

            assertThat(inGeo(redis, rider)).isFalse();
            assertThat(status(redis, rider)).isEqualTo("ON_BREAK");
        });
    }

    @Test
    void missingStateHashIsNotCreated() {
        run((sweeper, redis) -> {
            long rider = givenRider(redis, null, Duration.ofSeconds(31));

            sweepOnce(sweeper, redis);

            assertThat(inHeartbeat(redis, rider)).isFalse();
            assertThat(redis.hasKey(RedisKeys.riderState(rider))).isFalse();
        });
    }

    // ── 뽑은 뒤에 좌표가 들어온 경우 ──────────────────────────────────────

    @Test
    void scriptRechecksHeartbeatBeforeSweeping() {
        // 스위퍼가 ZRANGEBYSCORE 로 뽑은 직후 새 좌표가 들어온 상황. 뽑을 때 쓴 cutoff 를 그대로 넘긴다.
        contextRunner.run(context -> {
            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            RedisScript<Long> script = context.getBean("sweepRiderScript", RedisScript.class);
            long rider = givenRider(redis, RiderStatus.IDLE, Duration.ofSeconds(1));
            long cutoffWhenPicked = System.currentTimeMillis() - 30_000;

            Long outcome = redis.execute(script,
                    List.of(RedisKeys.RIDERS_GEO, RedisKeys.riderState(rider), RedisKeys.RIDERS_HEARTBEAT),
                    Long.toString(rider), Long.toString(cutoffWhenPicked));

            assertThat(SweepOutcome.fromCode(outcome)).isEqualTo(SweepOutcome.REFRESHED);
            assertThat(inGeo(redis, rider)).isTrue();
            assertThat(status(redis, rider)).isEqualTo(RiderStatus.IDLE.name());
        });
    }

    // ── 페이지 넘기기 ─────────────────────────────────────────────────────

    @Test
    void idleRiderBehindManyDeliveringRidersIsStillSwept() {
        // 배치 크기 3 에 조용한 배달 중 라이더 7명을 앞에 세운다. 매번 0 부터 읽으면 IDLE 까지 못 간다.
        run(3, (sweeper, redis) -> {
            for (int i = 0; i < 7; i++) {
                givenRider(redis, RiderStatus.DELIVERING, Duration.ofMinutes(10).minusSeconds(i));
            }
            long idle = givenRider(redis, RiderStatus.IDLE, Duration.ofMinutes(1));

            sweepOnce(sweeper, redis);

            assertThat(status(redis, idle)).isEqualTo(RiderStatus.OFFLINE.name());
        });
    }

    // ── 규칙 3번: 한 번에 한 대만 ─────────────────────────────────────────

    @Test
    void skipsWhenAnotherInstanceHoldsTheLock() {
        run((sweeper, redis) -> {
            long rider = givenRider(redis, RiderStatus.IDLE, Duration.ofSeconds(31));
            redis.opsForValue().set(RedisKeys.SWEEP_OFFLINE_LOCK, "geo-indexer:8192", Duration.ofSeconds(8));

            Optional<Map<SweepOutcome, Integer>> result = sweeper.sweep();

            assertThat(result).isEmpty();
            assertThat(status(redis, rider)).isEqualTo(RiderStatus.IDLE.name());
        });
    }

    @Test
    void lockIsLeftToExpireInsteadOfReleased() {
        run((sweeper, redis) -> {
            sweepOnce(sweeper, redis);

            assertThat(redis.opsForValue().get(RedisKeys.SWEEP_OFFLINE_LOCK)).isEqualTo("geo-indexer:8092");
            assertThat(redis.getExpire(RedisKeys.SWEEP_OFFLINE_LOCK)).isBetween(1L, 8L);
        });
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    private void run(BiConsumer<OfflineSweeper, StringRedisTemplate> body) {
        run(500, body);
    }

    private void run(int batchSize, BiConsumer<OfflineSweeper, StringRedisTemplate> body) {
        contextRunner.run(context -> {
            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
            @SuppressWarnings("unchecked")
            RedisScript<Long> script = context.getBean("sweepRiderScript", RedisScript.class);
            OfflineSweeper sweeper = new OfflineSweeper(redis, script,
                    new SweepProperties(Duration.ofSeconds(30), Duration.ofSeconds(8), batchSize, Duration.ofSeconds(6)),
                    new SimpleMeterRegistry(), "geo-indexer:8092");
            body.accept(sweeper, redis);
        });
    }

    /** 앞 테스트가 남긴 락이 있으면 스위퍼가 그냥 건너뛰니까 지우고 돌린다 */
    private static void sweepOnce(OfflineSweeper sweeper, StringRedisTemplate redis) {
        redis.delete(RedisKeys.SWEEP_OFFLINE_LOCK);
        assertThat(sweeper.sweep()).isPresent();
    }

    /** status 가 null 이면 해시를 안 만든다 */
    private long givenRider(StringRedisTemplate redis, RiderStatus status, Duration quietFor) {
        long riderId = Ids.newId();
        riders.add(riderId);
        String member = Long.toString(riderId);
        long seenAt = System.currentTimeMillis() - quietFor.toMillis();

        redis.opsForGeo().add(RedisKeys.RIDERS_GEO, new Point(LNG, LAT), member);
        redis.opsForZSet().add(RedisKeys.RIDERS_HEARTBEAT, member, seenAt);
        if (status != null) {
            redis.opsForHash().putAll(RedisKeys.riderState(riderId), Map.of(
                    RiderStateFields.STATUS, status.name(),
                    RiderStateFields.LAST_SEEN_AT, Long.toString(seenAt)));
        }
        return riderId;
    }

    private static boolean inGeo(StringRedisTemplate redis, long riderId) {
        return redis.opsForZSet().score(RedisKeys.RIDERS_GEO, Long.toString(riderId)) != null;
    }

    private static boolean inHeartbeat(StringRedisTemplate redis, long riderId) {
        return redis.opsForZSet().score(RedisKeys.RIDERS_HEARTBEAT, Long.toString(riderId)) != null;
    }

    private static String status(StringRedisTemplate redis, long riderId) {
        return (String) redis.opsForHash().get(RedisKeys.riderState(riderId), RiderStateFields.STATUS);
    }
}
