package com.delivery.orderapi.api;

import com.delivery.orderapi.domain.OrderCreateService;
import com.delivery.orderapi.domain.OrderStatus;

/** OR-01 응답. 프론트가 없으니 http/orders.http 로 눈으로 볼 그 화면이다 */
public record CreateOrderResponse(long orderId, OrderStatus status, String zoneId) {

    public static CreateOrderResponse from(OrderCreateService.Result result) {
        return new CreateOrderResponse(result.orderId(), result.status(), result.zoneId());
    }
}
