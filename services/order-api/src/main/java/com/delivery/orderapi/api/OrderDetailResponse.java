package com.delivery.orderapi.api;

import com.delivery.orderapi.domain.OrderQueryService;
import com.delivery.orderapi.domain.OrderStatus;

import java.time.Instant;
import java.util.List;

/**
 * OR-02 응답.
 *
 * <p>riderId 는 배차 전이면 null 로 나간다. 필드를 아예 빼버릴 수도 있는데, 그러면 보는 쪽에서
 * "아직 배차가 안 된 건지 응답이 잘린 건지" 를 구분 못 한다. null 이 더 정직하다.
 */
public record OrderDetailResponse(
        long orderId,
        OrderStatus status,
        Long riderId,
        int attempt,
        List<Entry> timeline
) {

    public record Entry(OrderStatus status, Instant at) {
    }

    public static OrderDetailResponse from(OrderQueryService.OrderDetail detail) {
        return new OrderDetailResponse(
                detail.orderId(),
                detail.status(),
                detail.riderId(),
                detail.attempt(),
                detail.timeline().stream()
                        .map(entry -> new Entry(entry.status(), entry.at()))
                        .toList());
    }
}
