package com.delivery.orderapi.domain;

import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * OR-02 주문 조회.
 *
 * <p>프론트가 없어서 이 응답이 사실상 화면이다. 그래서 "지금 상태" 만 주는 게 아니라
 * 어느 단계를 언제 지나왔는지(timeline)와 몇 번째 후보에서 잡혔는지(attempt)까지 같이 준다.
 *
 * <p>readOnly = true 로 둔 이유는 두 가지다. 하이버네이트가 더티 체킹용 스냅샷을 안 떠서
 * 조회가 조금 가벼워지고, 나중에 읽기 전용 복제본을 붙일 때 라우팅 힌트로 쓸 수 있다.
 */
@Service
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;

    @Transactional(readOnly = true)
    public OrderDetail findDetail(long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND,
                        "주문을 찾을 수 없어요 (orderId=%d)".formatted(orderId)));

        List<TimelineEntry> timeline = historyRepository.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(history -> new TimelineEntry(history.getStatus(), history.getOccurredAt()))
                .toList();

        return new OrderDetail(
                order.getOrderId(), order.getStatus(), order.getRiderId(), order.getAttempt(), timeline);
    }

    public record OrderDetail(
            long orderId,
            OrderStatus status,
            Long riderId,
            int attempt,
            List<TimelineEntry> timeline
    ) {
    }

    public record TimelineEntry(OrderStatus status, Instant at) {
    }
}
