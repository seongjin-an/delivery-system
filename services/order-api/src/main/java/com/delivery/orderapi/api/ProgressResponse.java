package com.delivery.orderapi.api;

import com.delivery.orderapi.domain.DeliveryProgressService;
import com.delivery.orderapi.domain.OrderStatus;

/**
 * OR-03, OR-04 응답.
 *
 * <p>처음 바꿨을 때와 이미 그 상태였을 때 둘 다 200 에 같은 모양이다 (기능 정의서 OR-03 예외 표).
 * 앱은 "이미 픽업됐어요" 와 "픽업됐어요" 를 가를 필요가 없다. 둘 다 다음 화면으로 넘어가면 된다.
 */
public record ProgressResponse(long orderId, OrderStatus status) {

    public static ProgressResponse from(DeliveryProgressService.Progress progress) {
        return new ProgressResponse(progress.orderId(), progress.status());
    }
}
