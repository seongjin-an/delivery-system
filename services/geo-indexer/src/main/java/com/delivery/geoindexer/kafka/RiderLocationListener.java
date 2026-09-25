package com.delivery.geoindexer.kafka;

import com.delivery.common.JsonUtil;
import com.delivery.common.event.RiderLocation;
import com.delivery.common.geo.Coordinates;
import com.delivery.geoindexer.index.IndexResult;
import com.delivery.geoindexer.index.RiderIndexer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
 * 밀린 좌표를 버리는 것({@link #isStale})도 같은 결정이다.
 *
 * <p>대신 조용히 넘어가지는 않는다. 실패는 ERROR 로 찍고 {@code geo_index_dropped_total} 을
 * 올린다. 그리고 인덱싱이 통째로 멈추면 {@code riders:online} 이 비어서 배차가 전부 실패하기
 * 때문에, 실제로는 곧바로 눈에 띈다.
 */
@Component
public class RiderLocationListener {

    private static final Logger log = LoggerFactory.getLogger(RiderLocationListener.class);

    private final RiderIndexer riderIndexer;
    /** 이보다 오래된 레코드는 읽지도 않고 버린다. 0 이면 끈다 */
    private final long maxAgeMillis;

    /** 받은 레코드 수. 아래 riders 와 빼면 배치 안에서 걷어낸 중복이 나온다 */
    private final Counter records;
    /** 실제로 레디스에 쓴 라이더 수 */
    private final Counter riders;
    private final Counter droppedMalformed;
    private final Counter droppedOutOfRange;
    private final Counter droppedRedisError;
    private final Counter droppedStale;

    public RiderLocationListener(RiderIndexer riderIndexer, MeterRegistry meterRegistry,
                                 @Value("${delivery.listener.rider-location.max-age}") Duration maxAge) {
        this.riderIndexer = riderIndexer;
        this.maxAgeMillis = maxAge.toMillis();
        this.records = Counter.builder("geo_index_records_total")
                .description("rider.location 에서 받은 레코드 수").register(meterRegistry);
        this.riders = Counter.builder("geo_index_riders_total")
                .description("레디스에 실제로 쓴 라이더 수 (배치 중복을 걷어낸 뒤)").register(meterRegistry);
        this.droppedMalformed = dropped(meterRegistry, "malformed");
        this.droppedOutOfRange = dropped(meterRegistry, "out_of_range");
        this.droppedRedisError = dropped(meterRegistry, "redis_error");
        this.droppedStale = dropped(meterRegistry, "stale");
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
            batch = "true")
    public void onLocations(List<ConsumerRecord<String, String>> batch, Acknowledgment ack) {
        records.increment(batch.size());

        List<RiderLocation> locations = parse(fresh(batch));
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

        // 성공이든 실패든 ack 한다. 위 클래스 주석의 이유 그대로다.
        ack.acknowledge();
    }

    /**
     * 너무 오래된 레코드는 버린다.
     *
     * <p>{@code auto-offset-reset: latest} 만 믿으면 안 된다. 그건 커밋된 오프셋이 <b>아예 없을 때만</b>
     * 먹는다. 2단계 실험에서 geo-indexer 를 5분 끄고 다시 켜니 커밋한 자리부터 90만 건을 다시 읽으면서
     * 310초 묵은 좌표를 레디스에 썼다. 따라잡는 53초 동안 라이더들이 5분 전 자리부터 빨리감기로 다시
     * 지나갔다. 그 사이 주문이 들어왔으면 5분 전 위치로 후보를 뽑았을 거다.
     *
     * <p>좌표 안의 {@code sentAt} 이 아니라 <b>레코드 타임스탬프</b>를 본다. 레코드 타임스탬프는
     * location-ingest 가 {@code send()} 할 때 서버 시계로 찍는다. {@code sentAt} 은 휴대폰 시계라서,
     * 시계가 30초 느린 폰 하나가 있으면 그 라이더 좌표가 전부 "오래됐다" 로 버려져서 지도에서 사라진다
     * (location-ingest 는 미래 시각만 고치고 과거 시각은 그대로 둔다).
     *
     * <p>JSON 을 읽기 전에 거른다. 밀렸을 때 버릴 걸 읽느라 시간을 쓰면 따라잡는 게 그만큼 늦어진다.
     */
    private List<String> fresh(List<ConsumerRecord<String, String>> batch) {
        long cutoff = System.currentTimeMillis() - maxAgeMillis;
        List<String> payloads = new ArrayList<>(batch.size());
        int stale = 0;
        for (ConsumerRecord<String, String> record : batch) {
            if (isStale(record, cutoff)) {
                stale++;
                continue;
            }
            payloads.add(record.value());
        }
        if (stale > 0) {
            droppedStale.increment(stale);
            // 한 건씩 안 찍는다. 밀렸을 땐 배치 500건이 통째로 여기 걸린다.
            log.debug("{}ms 넘게 묵은 좌표 {}건을 버렸다", maxAgeMillis, stale);
        }
        return payloads;
    }

    private boolean isStale(ConsumerRecord<String, String> record, long cutoff) {
        // 타임스탬프가 없으면(-1) 판단할 수가 없으니 살린다. 안 그러면 -1 < cutoff 라서 전부 버려진다.
        return maxAgeMillis > 0 && record.timestamp() >= 0 && record.timestamp() < cutoff;
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
