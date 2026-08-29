package com.delivery.orderapi.domain;

/**
 * 주문 상태. 기능 정의서 2.1.
 *
 * <pre>
 * CREATED ──▶ DISPATCHING ──▶ ASSIGNED ──▶ PICKED_UP ──▶ DELIVERED
 *    │             │
 *    │             └──▶ FAILED
 *    │
 *    └────────────────────────────────▶ CANCELLED   (DELIVERED 이전 어디서든)
 * </pre>
 */
public enum OrderStatus {
    CREATED,
    DISPATCHING,
    ASSIGNED,
    PICKED_UP,
    DELIVERED,
    FAILED,
    CANCELLED
}
