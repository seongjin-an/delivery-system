package com.delivery.orderapi.api;

import com.delivery.common.response.ApiResponse;
import com.delivery.common.web.CommonHeaders;
import com.delivery.orderapi.domain.OrderCreateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderCreateService orderCreateService;

    /**
     * OR-01 주문 생성.
     *
     * <p>같은 멱등키로 다시 부르면 새 주문을 만들지 않고 200 과 원래 orderId 를 준다.
     * 201 인지 200 인지로 "이번에 진짜 만들어진 건지" 를 호출하는 쪽이 구분할 수 있다.
     *
     * <p>헤더를 required = false 로 받는 건 스프링이 던지는 예외 대신 우리 에러 코드
     * (MISSING_IDEMPOTENCY_KEY) 로 응답하려는 것이다. 값이 빈 문자열인 경우까지 같이 걸린다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<CreateOrderResponse>> create(
            @RequestHeader(value = CommonHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {

        OrderCreateService.Result result = orderCreateService.create(idempotencyKey, request.toCommand());

        return ResponseEntity
                .status(result.isNew() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(CreateOrderResponse.from(result)));
    }
}
