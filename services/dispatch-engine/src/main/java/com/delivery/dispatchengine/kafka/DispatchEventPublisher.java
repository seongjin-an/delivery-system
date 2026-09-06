package com.delivery.dispatchengine.kafka;

import com.delivery.common.JsonUtil;
import com.delivery.common.KafkaTopics;
import com.delivery.common.Times;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * 배차 결과를 카프카로 알린다.
 *
 * <p>여기는 아웃박스를 안 쓴다. dispatch-engine 은 DB 를 안 쓰기 때문에 "DB 커밋과 발행이
 * 갈라지는 순간" 자체가 없다. 대신 발행에 실패하면 예외가 올라가서 카프카 메시지를 ack 하지
 * 않고, 재소비돼서 처음부터 다시 한다.
 */
@Component
@RequiredArgsConstructor
public class DispatchEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    /** 배차가 시작됐다 (order-api 가 상태를 DISPATCHING 으로 바꾼다) */
    public void publishDispatching(long orderId) {
        publish(KafkaTopics.ORDER_STATUS, orderId, Map.of(
                "orderId", orderId,
                "status", "DISPATCHING",
                "at", Times.now().toString()));
    }

    /**
     * 라이더가 수락했다 (DE-04).
     *
     * <p>토픽 두 개에 나눠 보낸다. {@code dispatch.assigned} 는 "배차가 이렇게 끝났다" 는
     * 사실이고 정산·지표가 이걸 본다. {@code order.status} 는 주문 상태 흐름이라 order-api 가
     * 픽업·완료와 같은 줄에 놓고 읽는다. 한 토픽에 몰면 정산이 픽업 이벤트까지 걸러내야 한다.
     */
    public void publishAssigned(long orderId, long riderId, long offerId, int attempt) {
        publish(KafkaTopics.DISPATCH_ASSIGNED, orderId, Map.of(
                "orderId", orderId,
                "riderId", riderId,
                "offerId", offerId,
                "attempt", attempt,
                "at", Times.now().toString()));
        publish(KafkaTopics.ORDER_STATUS, orderId, Map.of(
                "orderId", orderId,
                "riderId", riderId,
                "status", "ASSIGNED",
                "at", Times.now().toString()));
    }

    /** 후보를 다 썼는데 아무도 안 받았다 */
    public void publishFailed(long orderId, String reason) {
        publish(KafkaTopics.DISPATCH_FAILED, orderId, Map.of(
                "orderId", orderId,
                "reason", reason,
                "at", Instant.now().toString()));
    }

    private void publish(String topic, long orderId, Map<String, Object> payload) {
        // 키를 orderId 로 둬서 같은 주문의 이벤트가 한 파티션에 순서대로 들어가게 한다.
        kafkaTemplate.send(topic, Long.toString(orderId), JsonUtil.toJson(payload));
    }
}
