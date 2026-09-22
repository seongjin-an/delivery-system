package com.delivery.locationingest.ingest;

import com.delivery.common.event.RiderLocation;
import com.delivery.common.geo.Coordinates;
import com.delivery.common.geo.Zones;
import com.delivery.locationingest.kafka.LocationPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;

/**
 * LI-01 위치 수신. 검증하고, 걸러내고, 발행한다. 결과는 안 기다린다.
 */
@Service
public class LocationIngestService {

    private final MoveFilter moveFilter;
    private final LocationPublisher publisher;
    private final Clock clock;

    private final Map<MoveFilter.Verdict, Counter> verdicts = new EnumMap<>(MoveFilter.Verdict.class);
    private final Counter futureSentAt;

    public LocationIngestService(MoveFilter moveFilter, LocationPublisher publisher, Clock clock,
                                 MeterRegistry meterRegistry) {
        this.moveFilter = moveFilter;
        this.publisher = publisher;
        this.clock = clock;
        // verdict 라벨로 나눠 세면 "필터가 트래픽을 얼마나 걷어내는지" 가 그래프 한 장에 나온다.
        // skip 과 나머지의 비율이 기능 정의서가 말한 "트래픽 절반" 이 맞는지 확인하는 숫자다.
        for (MoveFilter.Verdict v : MoveFilter.Verdict.values()) {
            verdicts.put(v, Counter.builder("location_ingest_received_total")
                    .description("받은 좌표 수. verdict=skip 이면 발행 안 함")
                    .tag("verdict", v.name().toLowerCase())
                    .register(meterRegistry));
        }
        this.futureSentAt = Counter.builder("location_ingest_future_sent_at_total")
                .description("sentAt 이 서버 시각보다 미래라서 서버 시각으로 덮은 수")
                .register(meterRegistry);
    }

    public void ingest(long riderId, double lat, double lng, Instant sentAt) {
        // 범위 밖이면 400 INVALID_COORDINATE. 필터보다 먼저 봐야 한다. 순서가 바뀌면 발행도 못 할
        // 좌표가 캐시에 "직전 발행 좌표" 로 들어가고, 필터는 카프카에 없는 점을 기준으로 판정하게 된다.
        Coordinates.validate(lat, lng);

        MoveFilter.Verdict verdict = moveFilter.check(riderId, lat, lng);
        verdicts.get(verdict).increment();
        if (!verdict.publishes()) {
            return;
        }

        RiderLocation location = new RiderLocation(
                riderId, lat, lng, Zones.of(lat, lng), resolveSentAt(sentAt));
        publisher.publish(location, () -> moveFilter.forget(riderId, lat, lng));
    }

    /**
     * 폰 시계를 그대로 믿으면 안 된다. 사용자가 시간을 손으로 바꿔둔 폰이 1시간 뒤 시각을 보내면,
     * geo-indexer 가 그걸 lastSeenAt 과 heartbeat 점수로 쓰고, 오프라인 정리는 그 라이더를
     * 1시간 동안 못 치운다. 앱이 꺼져도 지도에 계속 남아서 제안을 받는 유령이 된다.
     *
     * <p>sentAt 을 아예 안 보낸 경우도 서버 시각으로 채운다. 이건 경고 지표는 안 올린다.
     */
    private Instant resolveSentAt(Instant sentAt) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (sentAt == null) {
            return now;
        }
        if (sentAt.isAfter(now)) {
            futureSentAt.increment();
            return now;
        }
        return sentAt;
    }
}
