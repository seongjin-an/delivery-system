package com.delivery.orderapi.api;

import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import com.delivery.common.web.CommonHeaders;
import com.delivery.common.web.GlobalExceptionHandler;
import com.delivery.orderapi.domain.OrderCreateService;
import com.delivery.orderapi.domain.OrderQueryService;
import com.delivery.orderapi.domain.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 껍데기만 본다. 도메인 규칙은 OrderCreateServiceTest 가 맡는다.
 *
 * <p>GlobalExceptionHandler 를 @Import 하는 이유: 이건 common 의 자동설정이 올려주는 빈이라
 * @WebMvcTest 의 컴포넌트 스캔에는 안 걸린다. 안 넣으면 400 이어야 할 응답이 500 으로 나온다.
 */
@WebMvcTest(OrderController.class)
@Import(GlobalExceptionHandler.class)
class OrderControllerTest {

    private static final long ORDER_ID = 558668931353510983L;
    private static final long RIDER_ID = 881520076849260058L;

    private static final String BODY = """
            {
              "storeId": "store-001",
              "storeLat": 37.498095,
              "storeLng": 127.027610,
              "destLat": 37.504198,
              "destLng": 127.048985,
              "priceKrw": 18000
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderCreateService orderCreateService;

    @MockitoBean
    private OrderQueryService orderQueryService;

    @Test
    void returns201WhenOrderIsCreated() throws Exception {
        given(orderCreateService.create(anyString(), any())).willReturn(
                new OrderCreateService.Result(ORDER_ID, OrderStatus.CREATED, "Z3749_12702", true));

        mockMvc.perform(post("/api/orders")
                        .header(CommonHeaders.IDEMPOTENCY_KEY, "idem-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.zoneId").value("Z3749_12702"))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    /** 같은 멱등키로 다시 부른 요청. 200 으로 구분해줘야 호출한 쪽이 "이번엔 안 만들어졌구나" 를 안다 */
    @Test
    void returns200WhenRequestIsReplayed() throws Exception {
        given(orderCreateService.create(anyString(), any())).willReturn(
                new OrderCreateService.Result(ORDER_ID, OrderStatus.CREATED, "Z3749_12702", false));

        mockMvc.perform(post("/api/orders")
                        .header(CommonHeaders.IDEMPOTENCY_KEY, "idem-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID));
    }

    @Test
    void returnsMissingIdempotencyKeyWhenHeaderIsAbsent() throws Exception {
        willThrow(new BusinessException(ErrorCode.MISSING_IDEMPOTENCY_KEY))
                .given(orderCreateService).create(eq(null), any());

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    void mapsInvalidCoordinateToBadRequest() throws Exception {
        willThrow(new BusinessException(ErrorCode.INVALID_COORDINATE))
                .given(orderCreateService).create(anyString(), any());

        mockMvc.perform(post("/api/orders")
                        .header(CommonHeaders.IDEMPOTENCY_KEY, "idem-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_COORDINATE"));
    }

    /** 본문 검증(@Valid)에 걸린 것도 같은 에러 코드로 나가야 한다 */
    @Test
    void mapsBeanValidationFailureToInvalidRequest() throws Exception {
        String noStoreId = BODY.replace("\"store-001\"", "\"\"");

        mockMvc.perform(post("/api/orders")
                        .header(CommonHeaders.IDEMPOTENCY_KEY, "idem-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noStoreId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ── OR-02 주문 조회 ───────────────────────────────────────────────────

    @Test
    void returnsOrderDetailWithTimeline() throws Exception {
        Instant createdAt = Instant.parse("2026-08-23T04:12:33.482Z");
        given(orderQueryService.findDetail(ORDER_ID)).willReturn(
                new OrderQueryService.OrderDetail(ORDER_ID, OrderStatus.ASSIGNED, RIDER_ID, 2, List.of(
                        new OrderQueryService.TimelineEntry(OrderStatus.CREATED, createdAt),
                        new OrderQueryService.TimelineEntry(OrderStatus.ASSIGNED, createdAt.plusSeconds(8)))));

        mockMvc.perform(get("/api/orders/{orderId}", ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID))
                .andExpect(jsonPath("$.data.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.data.riderId").value(RIDER_ID))
                .andExpect(jsonPath("$.data.attempt").value(2))
                .andExpect(jsonPath("$.data.timeline.length()").value(2))
                .andExpect(jsonPath("$.data.timeline[0].status").value("CREATED"))
                // 시각은 ISO-8601 문자열이라야 한다. 숫자로 나가면 기능 정의서 3.2 와 어긋난다.
                .andExpect(jsonPath("$.data.timeline[0].at").value("2026-08-23T04:12:33.482Z"));
    }

    @Test
    void returnsOrderNotFoundForUnknownOrder() throws Exception {
        willThrow(new BusinessException(ErrorCode.ORDER_NOT_FOUND))
                .given(orderQueryService).findDetail(ORDER_ID);

        mockMvc.perform(get("/api/orders/{orderId}", ORDER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    /** 숫자가 아닌 아이디는 스프링 타입 변환에서 걸린다. 그것도 우리 에러 코드로 나가야 한다 */
    @Test
    void mapsNonNumericOrderIdToInvalidRequest() throws Exception {
        mockMvc.perform(get("/api/orders/{orderId}", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
