package com.delivery.orderapi.domain;

import com.delivery.common.KafkaTopics;
import com.delivery.common.event.OrderCreated;
import com.delivery.orderapi.outbox.OutboxAppender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 주문 행과 아웃박스 행을 <b>한 트랜잭션</b>에 넣는다. OR-01 규칙 1번.
 *
 * <p>이 클래스를 따로 뺀 이유는 트랜잭션 경계를 좁게 잡으려는 것이다. 멱등키를 다루는 레디스
 * 왕복까지 트랜잭션 안에 들어가면, 레디스가 느려진 만큼 DB 커넥션과 락을 붙잡고 있게 된다.
 * DB 에 닿는 일만 여기 모아두고 바깥에서 부른다.
 *
 * <p>같은 클래스 안에서 @Transactional 메서드를 부르면 프록시를 안 거쳐서 트랜잭션이 안 걸린다.
 * 빈을 나눠둔 게 그것도 같이 피해준다.
 */
@Component
@RequiredArgsConstructor
public class OrderWriter {

    private final OrderRepository orderRepository;
    private final OutboxAppender outboxAppender;

    @Transactional
    public Order write(Order order) {
        Order saved = orderRepository.save(order);
        outboxAppender.append(
                KafkaTopics.ORDER_CREATED,
                saved.getOrderId(),
                Long.toString(saved.getOrderId()),  // 파티션 키 = orderId. 같은 주문의 순서를 지킨다
                toEvent(saved),
                saved.getCreatedAt());
        return saved;
    }

    private static OrderCreated toEvent(Order order) {
        return new OrderCreated(
                order.getOrderId(),
                order.getStoreId(),
                order.getStoreLat(),
                order.getStoreLng(),
                order.getDestLat(),
                order.getDestLng(),
                order.getZoneId(),
                order.getPriceKrw(),
                order.getCreatedAt());
    }
}
