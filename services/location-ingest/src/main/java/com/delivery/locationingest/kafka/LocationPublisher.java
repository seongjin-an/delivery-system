package com.delivery.locationingest.kafka;

import com.delivery.common.JsonUtil;
import com.delivery.common.KafkaTopics;
import com.delivery.common.event.RiderLocation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * rider.location 발행.
 *
 * <p>실패해도 예외를 안 올린다. 기능 정의서 LI-01 예외 표대로 라이더 앱에는 202 를 그대로 준다.
 * 앱을 재시도시켜봐야 그 좌표는 3초 뒤 다음 좌표보다 낡았다. 대신 지표를 올리고 콜백으로
 * 이동거리 필터에 알려서 다음 좌표가 무조건 나가게 한다.
 */
@Slf4j
@Component
public class LocationPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter failed;

    public LocationPublisher(KafkaTemplate<String, String> kafkaTemplate, MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.failed = Counter.builder("location_ingest_publish_failed_total")
                .description("rider.location 발행 실패 수. 라이더 앱에는 202 가 나갔다")
                .register(meterRegistry);
    }

    public void publish(RiderLocation location, Runnable onFailure) {
        // 키가 riderId 라야 같은 라이더 좌표가 한 파티션에 순서대로 쌓인다.
        // 안 그러면 geo-indexer 에서 오래된 좌표가 나중에 도착해서 라이더가 뒤로 순간이동한다.
        String key = Long.toString(location.riderId());
        try {
            kafkaTemplate.send(KafkaTopics.RIDER_LOCATION, key, JsonUtil.toJson(location))
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            fail(location, ex, onFailure);
                        }
                    });
        } catch (RuntimeException e) {
            // 브로커 메타데이터를 못 받으면 send() 가 비동기가 아니라 그 자리에서 터진다
            // (max.block.ms 만큼 기다린 뒤). 여기도 똑같이 삼킨다.
            fail(location, e, onFailure);
        }
    }

    private void fail(RiderLocation location, Throwable ex, Runnable onFailure) {
        failed.increment();
        onFailure.run();
        // 스택트레이스는 안 찍는다. 카프카가 내려가면 라이더 1천 명 기준으로 초당 300줄이 넘게 쌓여서
        // 로키가 먼저 숨이 막힌다. 원인은 메시지 한 줄로 충분하고, 얼마나 실패하는지는 지표로 본다.
        log.warn("rider.location 발행 실패, 버린다: riderId={} cause={}", location.riderId(), ex.toString());
    }
}
