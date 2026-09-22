package com.delivery.geoindexer.sweep;

import com.delivery.common.RedisKeys;
import com.delivery.common.Times;
import com.delivery.geoindexer.config.SweepProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * GI-02 오프라인 정리. 10초마다 좌표가 30초 넘게 안 온 라이더를 지도에서 뺀다.
 *
 * <p>이게 없으면 앱을 끄고 퇴근한 라이더가 riders:online 에 마지막 좌표 그대로 영원히 남는다.
 * status 가 IDLE 이라 후보로 계속 뽑히고, 제안이 가도 아무도 안 받아서 10초씩 날린다.
 * 점심 피크에 이런 사람이 후보 1순위로 몇 명만 끼어 있어도 배차가 수십 초씩 밀린다.
 */
@Slf4j
@Component
public class OfflineSweeper {

    private static final int KEY_COUNT = 3;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> sweepRiderScript;
    private final SweepProperties properties;
    private final String owner;

    private final Map<SweepOutcome, Counter> outcomes = new EnumMap<>(SweepOutcome.class);
    private final Counter ran;
    private final Counter lockHeld;
    private final Counter failed;
    private final Timer duration;

    public OfflineSweeper(StringRedisTemplate redis, RedisScript<Long> sweepRiderScript,
                          SweepProperties properties, MeterRegistry meterRegistry,
                          @Value("${spring.application.name:geo-indexer}:${server.port:8092}") String owner) {
        this.redis = redis;
        this.sweepRiderScript = sweepRiderScript;
        this.properties = properties;
        // 락 값에 누가 잡았는지 적어둔다. redis-cli GET lock:sweep:offline 한 번이면
        // scale.sh 로 3대 띄웠을 때 지금 어느 인스턴스가 돌고 있는지 바로 보인다.
        this.owner = owner;

        for (SweepOutcome outcome : SweepOutcome.values()) {
            outcomes.put(outcome, Counter.builder("geo_sweep_riders_total")
                    .description("오프라인 정리에서 라이더별로 어떻게 처리했는지")
                    .tag("outcome", outcome.name().toLowerCase())
                    .register(meterRegistry));
        }
        this.ran = runs(meterRegistry, "ran");
        this.lockHeld = runs(meterRegistry, "lock_held");
        this.failed = runs(meterRegistry, "failed");
        this.duration = Timer.builder("geo_sweep_duration")
                .description("오프라인 정리 한 번에 걸린 시간 (락을 잡은 경우만)")
                .register(meterRegistry);
    }

    private static Counter runs(MeterRegistry registry, String result) {
        return Counter.builder("geo_sweep_runs_total")
                .description("오프라인 정리 주기. lock_held 는 다른 인스턴스가 돌고 있어서 건너뛴 것")
                .tag("result", result)
                .register(registry);
    }

    /**
     * fixedDelay 라서 한 번이 오래 걸려도 다음 실행이 겹치지 않는다.
     *
     * <p>첫 실행을 늦추는 이유: geo-indexer 를 재시작해보니 새 컨슈머가 파티션을 받는 데 35초가 걸렸다.
     * 그동안 heartbeat 는 안 올라가는데 스위퍼가 바로 돌면, 멀쩡히 좌표를 보내고 있는 라이더가
     * 전부 오프라인이 되고 idleSince 까지 날아간다. 우리가 재시작한 걸 라이더가 퇴근한 걸로 착각하는 거다.
     */
    @Scheduled(fixedDelayString = "${delivery.sweep.interval}",
            initialDelayString = "${delivery.sweep.initial-delay}")
    public void sweepOnSchedule() {
        try {
            sweep();
        } catch (Exception e) {
            // 스프링 스케줄러는 예외가 나도 다음 주기를 돌려주긴 한다. 그래도 여기서 잡는 건
            // 레디스가 끊겼을 때 스택트레이스 대신 한 줄과 지표로 남기려는 것이다.
            failed.increment();
            log.error("오프라인 정리 실패, 다음 주기에 다시 한다: {}", e.toString());
        }
    }

    /**
     * 한 번 돈다. 다른 인스턴스가 락을 들고 있으면 아무것도 안 하고 빈 값을 돌려준다.
     */
    public Optional<Map<SweepOutcome, Integer>> sweep() {
        if (!tryLock()) {
            lockHeld.increment();
            return Optional.empty();
        }
        ran.increment();
        return Optional.of(duration.record(this::sweepAll));
    }

    /**
     * 락은 일부러 안 푼다. 8초 뒤 저절로 풀리고, 다음 주기는 10초 뒤라 그 사이에 겹칠 일이 없다.
     * 끝나자마자 풀면 주기가 2초 어긋난 다른 인스턴스가 바로 잡아서 한 주기에 두 번 돈다.
     */
    private boolean tryLock() {
        return Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(RedisKeys.SWEEP_OFFLINE_LOCK, owner, properties.lockTtl()));
    }

    private Map<SweepOutcome, Integer> sweepAll() {
        long started = System.nanoTime();
        long cutoff = Times.now().toEpochMilli() - properties.offlineAfter().toMillis();
        Map<SweepOutcome, Integer> counts = new EnumMap<>(SweepOutcome.class);

        // 앞에서부터 batchSize 씩 읽는데, 처리하고도 구간에 남는 애들(배달 중, 제안 중)만큼 건너뛴다.
        // 매번 0 부터 읽으면 조용한 배달 중 라이더 500명이 앞자리를 다 차지하고 있을 때
        // 그 뒤의 IDLE 라이더는 영원히 정리가 안 된다.
        long offset = 0;
        while (true) {
            Set<String> riderIds = redis.opsForZSet().rangeByScore(
                    RedisKeys.RIDERS_HEARTBEAT, Double.NEGATIVE_INFINITY, cutoff, offset, properties.batchSize());
            if (riderIds == null || riderIds.isEmpty()) {
                break;
            }

            List<Object> results = redis.executePipelined(pipeline(riderIds, cutoff));
            for (Object result : results) {
                SweepOutcome outcome = SweepOutcome.fromCode(((Number) result).longValue());
                counts.merge(outcome, 1, Integer::sum);
                outcomes.get(outcome).increment();
                if (outcome.staysInHeartbeat()) {
                    offset++;
                }
            }

            if (riderIds.size() < properties.batchSize()) {
                break;
            }
            if (System.nanoTime() - started > properties.timeBudget().toNanos()) {
                log.warn("오프라인 정리가 {}ms 를 넘겨서 남은 건 다음 주기로 넘긴다: {}",
                        properties.timeBudget().toMillis(), counts);
                break;
            }
        }

        if (counts.getOrDefault(SweepOutcome.OFFLINE, 0) > 0 || counts.containsKey(SweepOutcome.UNKNOWN)) {
            log.info("오프라인 정리: {}", counts);
        }
        return counts;
    }

    /** GI-01 과 같은 이유로 EVALSHA 가 아니라 EVAL 이다 (RiderIndexer 주석 참고) */
    private RedisCallback<Object> pipeline(Set<String> riderIds, long cutoff) {
        byte[] script = sweepRiderScript.getScriptAsString().getBytes(StandardCharsets.UTF_8);
        byte[] geoKey = bytes(RedisKeys.RIDERS_GEO);
        byte[] heartbeatKey = bytes(RedisKeys.RIDERS_HEARTBEAT);
        byte[] cutoffArg = bytes(Long.toString(cutoff));

        return connection -> {
            for (String riderId : riderIds) {
                connection.scriptingCommands().eval(
                        script, ReturnType.INTEGER, KEY_COUNT,
                        geoKey,
                        bytes(RedisKeys.riderState(Long.parseLong(riderId))),
                        heartbeatKey,
                        bytes(riderId),
                        cutoffArg);
            }
            return null;
        };
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
