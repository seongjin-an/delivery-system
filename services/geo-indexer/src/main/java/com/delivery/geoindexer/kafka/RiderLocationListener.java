package com.delivery.geoindexer.kafka;

import com.delivery.common.JsonUtil;
import com.delivery.common.event.RiderLocation;
import com.delivery.common.geo.Coordinates;
import com.delivery.geoindexer.index.IndexResult;
import com.delivery.geoindexer.index.RiderIndexer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code rider.location} 을 배치로 받아 레디스 인덱스에 반영한다. GI-01 의 입구.
 *
 * <p><b>왜 한 건씩이 아니라 배치인가.</b> 라이더 1000명이 3초마다 좌표를 보내면 초당 330건이다.
 * 한 건씩 처리하면 레디스 왕복이 초당 330번 생기는데, 그중 상당수는 <b>같은 라이더의 좌표</b>다.
 * 한 번에 받아서 라이더별로 마지막 것만 남기면 그 왕복이 한 번으로 줄어든다.
 *
 * <p><b>실패하면 재시도하지 않고 그냥 ack 한다.</b> 다른 컨슈머들과 정반대라 설명이 필요하다.
 * {@code order.created} 는 한 건을 놓치면 손님의 주문이 사라지니까 DLT 까지 가며 붙잡는다.
 * 위치는 반대다 — 한 점을 놓쳐도 3초 뒤에 다음 점이 온다. 그런데 재시도하면 그동안 파티션이
 * 막혀서 <b>밀린 좌표가 더 쌓인다.</b> 신선도가 전부인 데이터에서 밀리는 건 잃는 것보다 나쁘다.
 * {@code auto-offset-reset: latest} 로 "밀린 건 따라잡지 않는다" 고 정해둔 것과 같은 결정이다.
 *
 * <p>대신 조용히 넘어가지는 않는다. 실패는 ERROR 로 찍고 {@code geo_index_dropped_total} 을
 * 올린다. 그리고 인덱싱이 통째로 멈추면 {@code riders:online} 이 비어서 배차가 전부 실패하기
 * 때문에, 실제로는 곧바로 눈에 띈다.
 */
@Component
public class RiderLocationListener {

    private static final Logger log = LoggerFactory.getLogger(RiderLocationListener.class);

    private final RiderIndexer riderIndexer;

    /** 받은 레코드 수. 아래 riders 와 빼면 배치 안에서 걷어낸 중복이 나온다 */
    private final Counter records;
    /** 실제로 레디스에 쓴 라이더 수 */
    private final Counter riders;
    private final Counter droppedMalformed;
    private final Counter droppedOutOfRange;
    private final Counter droppedRedisError;

    /*
     * 2단계 실험용 지표 두 개. 카프카와 래빗엠큐를 같은 잣대로 재려고 둘 다 여기(공통 입구)에 뒀다.
     *
     * orderRegression: 이미 더 새 좌표를 처리한 라이더의 옛 좌표가 뒤늦게 들어온 횟수.
     *   RiderIndexer 는 sentAt 을 안 보고 "나중에 온 게 최신" 으로 치니까, 이게 곧 레디스에서
     *   라이더가 뒤로 순간이동한 횟수다. 카프카는 파티션 키가 riderId 라 0 이어야 한다.
     * staleness: 좌표가 앱에서 나와(sentAt) 여기 도착할 때까지 걸린 시간. 밀렸다 따라잡을 때
     *   몇 초 묵은 좌표를 레디스에 쓰고 있었는지 본다.
     */
    private final ConcurrentHashMap<Long, Instant> lastSentAt = new ConcurrentHashMap<>();
    private final Counter orderRegression;
    private final Timer staleness;

    public RiderLocationListener(RiderIndexer riderIndexer, MeterRegistry meterRegistry) {
        this.riderIndexer = riderIndexer;
        this.records = Counter.builder("geo_index_records_total")
                .description("rider.location 에서 받은 레코드 수").register(meterRegistry);
        this.riders = Counter.builder("geo_index_riders_total")
                .description("레디스에 실제로 쓴 라이더 수 (배치 중복을 걷어낸 뒤)").register(meterRegistry);
        this.droppedMalformed = dropped(meterRegistry, "malformed");
        this.droppedOutOfRange = dropped(meterRegistry, "out_of_range");
        this.droppedRedisError = dropped(meterRegistry, "redis_error");
        this.orderRegression = Counter.builder("geo_index_order_regression_total")
                .description("같은 라이더의 더 새 좌표보다 늦게 도착한 옛 좌표 수").register(meterRegistry);
        this.staleness = Timer.builder("geo_index_staleness")
                .description("sentAt 부터 geo-indexer 도착까지")
                .publishPercentiles(0.5, 0.99)
                .register(meterRegistry);
    }

    private static Counter dropped(MeterRegistry registry, String reason) {
        return Counter.builder("geo_index_dropped_total")
                .description("인덱싱하지 못하고 버린 레코드 수")
                .tag("reason", reason)
                .register(registry);
    }

    @KafkaListener(
            topics = "${delivery.listener.rider-location.topic}",
            concurrency = "${delivery.listener.rider-location.concurrency}",
            // 2단계 실험: transport=rabbit 이면 카프카 컨슈머를 안 띄운다
            autoStartup = "#{'${delivery.listener.rider-location.transport:kafka}' == 'kafka'}",
            batch = "true")
    public void onLocations(List<String> payloads, Acknowledgment ack) {
        handle(payloads);
        // 성공이든 실패든 ack 한다. 위 클래스 주석의 이유 그대로다.
        ack.acknowledge();
    }

    /** 카프카와 래빗엠큐 리스너가 같이 쓰는 본문. 예외를 밖으로 안 던진다 */
    public void handle(List<String> payloads) {
        records.increment(payloads.size());

        List<RiderLocation> locations = parse(payloads);
        measure(locations);
        try {
            IndexResult result = riderIndexer.index(locations);
            riders.increment(result.indexed());

            log.debug("위치 인덱싱: 받음={} 씀={} 중복={} 새로온라인={}",
                    result.received(), result.indexed(), result.deduped(), result.cameOnline());
        } catch (Exception e) {
            droppedRedisError.increment(locations.size());
            log.error("배치 {}건을 인덱싱하지 못했다. 재시도하지 않고 버린다 — "
                    + "3초 뒤 다음 좌표가 온다", locations.size(), e);
        }
    }

    private void measure(List<RiderLocation> locations) {
        Instant now = Instant.now();
        for (RiderLocation location : locations) {
            if (location.sentAt() == null) {
                continue;
            }
            staleness.record(Duration.between(location.sentAt(), now));
            // compute 로 해야 한다. 래빗엠큐는 컨슈머 스레드 셋이 같은 라이더를 동시에 만질 수 있다.
            lastSentAt.compute(location.riderId(), (id, prev) -> {
                if (prev != null && location.sentAt().isBefore(prev)) {
                    orderRegression.increment();
                    return prev;
                }
                return location.sentAt();
            });
        }
    }

    /**
     * 못 읽는 레코드 하나가 배치 전체를 못 쓰게 만들면 안 된다.
     *
     * <p>좌표 검증을 여기서 또 하는 건 location-ingest 를 못 믿어서가 아니라, 토픽에 누가
     * 뭘 넣을지 모르기 때문이다. 실험하다가 손으로 넣어본 값이나 예전 포맷이 그대로 남아 있을
     * 수 있다. 그런 게 GEOADD 로 들어가면 레디스가 거절하면서 <b>파이프라인 전체가 실패한다</b> —
     * 좌표 하나 때문에 멀쩡한 라이더 200명이 같이 날아간다.
     */
    private List<RiderLocation> parse(List<String> payloads) {
        List<RiderLocation> locations = new ArrayList<>(payloads.size());
        for (String payload : payloads) {
            RiderLocation location;
            try {
                location = JsonUtil.fromJson(payload, RiderLocation.class);
            } catch (Exception e) {
                droppedMalformed.increment();
                log.warn("rider.location 레코드를 읽지 못했다: {}", abbreviate(payload));
                continue;
            }
            if (!Coordinates.isValid(location.lat(), location.lng())) {
                droppedOutOfRange.increment();
                log.warn("서비스 지역 밖 좌표라 버린다: riderId={} lat={} lng={}",
                        location.riderId(), location.lat(), location.lng());
                continue;
            }
            locations.add(location);
        }
        return locations;
    }

    private static String abbreviate(String payload) {
        if (payload == null) {
            return "null";
        }
        return payload.length() <= 200 ? payload : payload.substring(0, 200) + "...";
    }
}
