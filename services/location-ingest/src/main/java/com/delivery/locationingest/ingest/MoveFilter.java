package com.delivery.locationingest.ingest;

import com.delivery.common.geo.Coordinates;
import com.delivery.locationingest.config.IngestProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * 이동거리 필터. 기능 정의서 LI-01 규칙 1, 2, 4번.
 *
 * <p>비교 기준은 "직전에 받은 좌표" 가 아니라 "직전에 발행한 좌표" 다. 받은 좌표랑 비교하면
 * 3초에 10m 씩 천천히 움직이는 라이더는 매번 15m 미만이라 영영 발행이 안 된다.
 * 30초 동안 100m 를 갔는데 지도에서는 제자리에 서 있는 거다.
 *
 * <p>레디스가 아니라 인스턴스 메모리에 들고 있다. 레디스에 두면 요청마다 왕복이 생겨서
 * 이 서비스가 무상태라는 게 깨진다 (시나리오 A 의 기준선이 이 서비스다).
 */
@Component
public class MoveFilter {

    public enum Verdict {
        /** 캐시에 없는 라이더. 처음 왔거나, 30분 만에 왔거나, 직전 발행이 실패했다 */
        FIRST,
        /** 15m 이상 움직였다 */
        MOVED,
        /** 안 움직였지만 오래 조용했다. heartbeat 가 끊기지 않게 한 번 보낸다 */
        KEEPALIVE,
        /** 건너뛴다 */
        SKIP;

        public boolean publishes() {
            return this != SKIP;
        }
    }

    private record Published(double lat, double lng, long atMillis) {
    }

    private final Cache<Long, Published> lastPublished;
    private final double minMoveMeters;
    private final long maxSilenceMillis;
    private final Clock clock;

    public MoveFilter(IngestProperties properties, Clock clock) {
        this.lastPublished = Caffeine.newBuilder()
                .maximumSize(properties.cacheMaxRiders())
                // 접근이 아니라 쓰기 기준이다. 서 있는 라이더도 KEEPALIVE 로 10초마다 다시 쓰이니까
                // "30분 넘게 안 온 라이더" 랑 결과가 같고, 이쪽이 동작을 예측하기 쉽다.
                .expireAfterWrite(properties.cacheExpireAfter())
                .build();
        this.minMoveMeters = properties.minMoveMeters();
        this.maxSilenceMillis = properties.maxSilence().toMillis();
        this.clock = clock;
    }

    /**
     * 이번 좌표를 발행할지 정하고, 발행할 거면 캐시를 이 좌표로 바꾼다.
     *
     * <p>판정이랑 갱신을 compute 하나로 묶었다. 따로 하면 같은 라이더 요청 두 개가 거의 동시에
     * 들어왔을 때(앱이 재시도한 경우) 둘 다 "처음 왔네" 로 보고 둘 다 발행한다.
     */
    public Verdict check(long riderId, double lat, double lng) {
        long now = clock.millis();
        Verdict[] verdict = new Verdict[1];
        lastPublished.asMap().compute(riderId, (id, prev) -> {
            verdict[0] = decide(prev, lat, lng, now);
            return verdict[0].publishes() ? new Published(lat, lng, now) : prev;
        });
        return verdict[0];
    }

    /**
     * 발행에 실패했으면 캐시에서 지운다.
     *
     * <p>안 지우면 카프카에는 안 들어갔는데 필터는 보냈다고 믿는다. 그다음 좌표가 15m 안이면
     * 다 걸러져서, 이 라이더 위치가 최대 10초 동안 옛날 자리에 멈춰 있게 된다.
     * 지워두면 다음 좌표는 FIRST 로 무조건 나간다.
     *
     * <p>좌표가 같을 때만 지운다. 콜백이 늦게 와서 그 사이 새 좌표로 캐시가 바뀌었으면
     * 그건 건드리면 안 된다.
     */
    public void forget(long riderId, double lat, double lng) {
        lastPublished.asMap().computeIfPresent(riderId,
                (id, cur) -> cur.lat() == lat && cur.lng() == lng ? null : cur);
    }

    private Verdict decide(Published prev, double lat, double lng, long now) {
        if (prev == null) {
            return Verdict.FIRST;
        }
        if (Coordinates.distanceMeters(prev.lat(), prev.lng(), lat, lng) >= minMoveMeters) {
            return Verdict.MOVED;
        }
        if (now - prev.atMillis() >= maxSilenceMillis) {
            return Verdict.KEEPALIVE;
        }
        return Verdict.SKIP;
    }
}
