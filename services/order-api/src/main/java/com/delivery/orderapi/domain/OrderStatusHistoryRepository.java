package com.delivery.orderapi.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Long> {

    /** 넣은 순서대로. 같은 밀리초에 두 번 바뀌어도 id 순이면 순서가 안 흔들린다 */
    List<OrderStatusHistory> findByOrderIdOrderByIdAsc(long orderId);
}
