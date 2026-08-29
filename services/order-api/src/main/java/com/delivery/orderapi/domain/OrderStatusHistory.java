package com.delivery.orderapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 주문 상태가 바뀔 때마다 한 줄씩 쌓이는 기록. OR-02 의 timeline 이 이걸 읽는다.
 *
 * <p>왜 따로 테이블이 필요하냐면, {@code orders} 행은 항상 "지금 상태" 하나만 들고 있어서다.
 * 상태가 CREATED → DISPATCHING → ASSIGNED 로 넘어가면 앞의 두 개는 덮여서 사라진다.
 * 그런데 고객 지원에서 제일 자주 묻는 게 "이 주문 왜 이렇게 오래 걸렸어요?" 라서,
 * 각 단계에 언제 들어갔는지가 남아 있어야 답을 할 수 있다.
 *
 * <p>상태마다 컬럼을 따로 두는 방법(created_at, assigned_at, picked_up_at ...)도 있는데
 * 그렇게 하면 상태가 늘 때마다 컬럼이 늘고, 같은 상태를 두 번 지나가는 경우를 못 담는다.
 * 배차는 후보가 바뀌면서 DISPATCHING 을 여러 번 지나가니까 행으로 쌓는 쪽이 맞다.
 *
 * <p>PK 가 자동증가 정수인 건 아웃박스와 같은 이유다. 읽을 때 넣은 순서 그대로 나와야 한다.
 */
@Entity
@Table(name = "order_status_history", indexes = {
        // timeline 은 항상 "이 주문의 기록을 넣은 순서대로" 읽는다. 이 인덱스 하나로 정렬까지 끝난다.
        @Index(name = "idx_history_order", columnList = "order_id, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OrderStatus status;

    /** 그 상태로 넘어간 시각 */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    private OrderStatusHistory(long orderId, OrderStatus status, Instant occurredAt) {
        this.orderId = orderId;
        this.status = status;
        this.occurredAt = occurredAt;
    }

    public static OrderStatusHistory of(long orderId, OrderStatus status, Instant occurredAt) {
        return new OrderStatusHistory(orderId, status, occurredAt);
    }
}
