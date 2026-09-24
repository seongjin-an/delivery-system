package com.delivery.orderapi.kafka;

import com.delivery.common.JsonUtil;
import com.delivery.common.KafkaTopics;
import com.delivery.common.event.DispatchAssigned;
import com.delivery.common.event.DispatchFailed;
import com.delivery.common.event.OrderStatusChanged;
import com.delivery.orderapi.domain.DispatchResultService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * OR-07 배차 결과를 받는 입구.
 *
 * <p>수동 ack 다. DB 에 반영한 다음에 오프셋을 옮긴다. 예외는 잡지 않고 올려서
 * 공통 에러 핸들러가 3회 백오프 뒤 DLT 로 보내게 한다 (MySQL 이 잠깐 안 붙는 경우).
 */
@Component
@RequiredArgsConstructor
public class DispatchResultListener {

    private final DispatchResultService dispatchResultService;

    @KafkaListener(topics = KafkaTopics.DISPATCH_ASSIGNED)
    public void onAssigned(String payload, Acknowledgment ack) {
        DispatchAssigned event = JsonUtil.fromJson(payload, DispatchAssigned.class);
        dispatchResultService.markAssigned(event.orderId(), event.riderId(), event.attempt(), event.at());
        ack.acknowledge();
    }

    @KafkaListener(topics = KafkaTopics.DISPATCH_FAILED)
    public void onFailed(String payload, Acknowledgment ack) {
        DispatchFailed event = JsonUtil.fromJson(payload, DispatchFailed.class);
        dispatchResultService.markFailed(event.orderId(), event.attempt(), event.reason(), event.at());
        ack.acknowledge();
    }

    /**
     * order.status 에서는 DISPATCHING 하나만 쓴다. 기능 정의서 OR-07 엔 없는 부분이다.
     *
     * <p>OR-02 조회의 timeline 예시에 DISPATCHING 이 들어 있는데, 그걸 order-api 가 알 길이 이 토픽뿐이다.
     * 안 받으면 주문이 CREATED 에서 ASSIGNED 로 바로 건너뛰고, 제안이 다섯 번 돌아서 50초 걸린 주문도
     * timeline 에는 "접수하고 50초 뒤 배차" 로만 보인다.
     *
     * <p>나머지 값은 버린다. ASSIGNED 는 attempt 가 실린 dispatch.assigned 쪽으로 받고,
     * PICKED_UP 과 DELIVERED 는 order-api 가 직접 바꾸면서 내보내는 것이라 우리 발행을 우리가 다시 받는 셈이다.
     */
    @KafkaListener(topics = KafkaTopics.ORDER_STATUS)
    public void onStatus(String payload, Acknowledgment ack) {
        OrderStatusChanged event = JsonUtil.fromJson(payload, OrderStatusChanged.class);
        if ("DISPATCHING".equals(event.status())) {
            dispatchResultService.markDispatching(event.orderId(), event.at());
        }
        ack.acknowledge();
    }
}
