package com.delivery.orderapi.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Long> {

    /** 넣은 순서대로. 같은 밀리초에 두 번 바뀌어도 id 순이면 순서가 안 흔들린다 */
    List<OrderStatusHistory> findByOrderIdOrderByIdAsc(long orderId);

    /** OR-04 가 배차 확정 시각을 꺼낼 때 쓴다. OR-07 이 상태가 실제로 바뀔 때만 적어서 한 줄뿐이다 */
    Optional<OrderStatusHistory> findFirstByOrderIdAndStatusOrderByIdAsc(long orderId, OrderStatus status);
}
