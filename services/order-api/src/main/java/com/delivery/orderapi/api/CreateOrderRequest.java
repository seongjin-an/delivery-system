package com.delivery.orderapi.api;

import com.delivery.orderapi.domain.OrderCreateService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * OR-01 요청 본문.
 *
 * <p>zoneId 는 여기 없다. 서버가 가게 좌표로 계산한다(기능 정의서 3.4). 클라이언트가 보낸 걸 쓰면
 * 앱 버전마다 다른 존이 찍혀서, 같은 자리에서 들어온 주문이 서로 다른 존으로 집계된다.
 *
 * <p>좌표 범위 검사는 여기 애노테이션으로 안 하고 도메인에서 Coordinates 로 한다.
 * 위치를 받는 곳이 order-api 말고 location-ingest 도 있어서, 규칙을 한 군데에 둬야 안 어긋난다.
 */
public record CreateOrderRequest(
        @NotBlank(message = "가게 아이디가 필요해요")
        String storeId,

        double storeLat,
        double storeLng,
        double destLat,
        double destLng,

        @Positive(message = "주문 금액은 0보다 커야 해요")
        int priceKrw
) {

    public OrderCreateService.NewOrder toCommand() {
        return new OrderCreateService.NewOrder(
                storeId, storeLat, storeLng, destLat, destLng, priceKrw);
    }
}
