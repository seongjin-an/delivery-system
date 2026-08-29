package com.delivery.orderapi.api;

import com.delivery.common.response.ApiResponse;
import com.delivery.common.web.CommonHeaders;
import com.delivery.orderapi.domain.OrderCreateService;
import com.delivery.orderapi.domain.OrderQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final OrderQueryService orderQueryService;

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

    /**
     * OR-02 주문 조회.
     *
     * <p>프론트가 없으니 이 응답이 사실상 화면이다. 지금 상태만이 아니라 어느 단계를 언제
     * 지나왔는지(timeline)와 몇 번째 후보에서 잡혔는지(attempt)까지 같이 준다.
     *
     * <p>orderId 가 숫자가 아니면 스프링이 타입 변환에서 걸러내고, 그걸 GlobalExceptionHandler 가
     * 400 INVALID_REQUEST 로 바꾼다. 없는 주문이면 404 ORDER_NOT_FOUND 다.
     */
    @GetMapping("/{orderId}")
    public ApiResponse<OrderDetailResponse> get(@PathVariable long orderId) {
        return ApiResponse.ok(OrderDetailResponse.from(orderQueryService.findDetail(orderId)));
    }
}
