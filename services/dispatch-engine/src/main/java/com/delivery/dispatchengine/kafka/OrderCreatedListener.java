package com.delivery.dispatchengine.kafka;

import com.delivery.common.JsonUtil;
import com.delivery.common.event.OrderCreated;
import com.delivery.dispatchengine.DispatchService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * {@code order.created} 를 받아 배차를 시작한다. DE-01 의 입구.
 *
 * <p>수동 ack 다. 배차가 끝나야 오프셋을 옮긴다. 자동 커밋이면 배차 도중에 프로세스가 죽었을 때
 * 그 주문이 그대로 사라진다.
 *
 * <p>예외를 잡지 않고 그대로 올린다. 공통 에러 핸들러가 받아서 BusinessException 이면 바로 DLT 로,
 * 나머지는 3회 백오프 뒤 DLT 로 보낸다.
 */
@Component
@RequiredArgsConstructor
public class OrderCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedListener.class);

    private final DispatchService dispatchService;

    @KafkaListener(
            topics = "${delivery.listener.order-created.topic}",
            concurrency = "${delivery.listener.order-created.concurrency}")
    public void onOrderCreated(String payload, Acknowledgment ack) {
        OrderCreated order = JsonUtil.fromJson(payload, OrderCreated.class);
        log.debug("주문 수신: orderId={} zone={}", order.orderId(), order.zoneId());

        dispatchService.dispatch(order);
        ack.acknowledge();
    }
}
