package com.delivery.settlementservice.kafka;

import com.delivery.common.JsonUtil;
import com.delivery.common.event.DeliveryCompleted;
import com.delivery.settlementservice.domain.SettlementWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * SE-01 입구. delivery.completed 를 받아 정산에 넣는다.
 *
 * <p>수동 ack 다. DB 커밋이 끝난 다음 오프셋을 옮긴다. 예외는 잡지 않고 올려서 공통 에러 핸들러가
 * 처리한다 — 이벤트가 틀렸으면(BusinessException) 곧바로 DLT, MySQL 이 잠깐 안 붙으면 3회 백오프.
 */
@Component
public class DeliveryCompletedListener {

    private final SettlementWriter writer;
    private final Counter recorded;
    private final Counter duplicates;

    public DeliveryCompletedListener(SettlementWriter writer, MeterRegistry registry) {
        this.writer = writer;
        this.recorded = Counter.builder("settlement_recorded_total")
                .description("처음 본 배달이라 합계에 더한 수").register(registry);
        // 리플레이하면 이게 오른다. 평소에 오르면 같은 이벤트가 두 번 오고 있다는 뜻이다
        this.duplicates = Counter.builder("settlement_duplicate_total")
                .description("이미 집계한 배달이라 건너뛴 수").register(registry);
    }

    @KafkaListener(
            topics = "${delivery.listener.delivery-completed.topic}",
            concurrency = "${delivery.listener.delivery-completed.concurrency}")
    public void onDeliveryCompleted(String payload, Acknowledgment ack) {
        DeliveryCompleted event = JsonUtil.fromJson(payload, DeliveryCompleted.class);
        if (writer.record(event)) {
            recorded.increment();
        } else {
            duplicates.increment();
        }
        ack.acknowledge();
    }
}
