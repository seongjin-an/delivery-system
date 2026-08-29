package com.delivery.common.exception;

/**
 * 기능 정의서 3.6 의 에러 코드 표를 그대로 옮긴 것.
 *
 * <p>HTTP 상태를 int 로 들고 있는 이유: 이 enum 은 컨슈머(웹이 아닌 곳)에서도 던져지는데,
 * 여기서 스프링 웹의 HttpStatus 를 참조하면 common 이 웹 모듈에 묶인다.
 * 상태 코드를 실제 응답으로 바꾸는 건 GlobalExceptionHandler 한 곳에서만 한다.
 *
 * <p>defaultMessage 는 라이더에게 그대로 보일 말이라 존댓말로 적었다.
 * 상황을 더 자세히 알려줘야 하면 던지는 쪽에서 메시지를 따로 넘기면 된다.
 */
public enum ErrorCode {

    INVALID_COORDINATE(400, "좌표가 서비스 지역을 벗어났어요"),
    MISSING_IDEMPOTENCY_KEY(400, "Idempotency-Key 헤더가 필요해요"),
    INVALID_REQUEST(400, "요청 형식이 올바르지 않아요"),

    NOT_YOUR_OFFER(403, "이 제안은 당신 것이 아니에요"),
    NOT_YOUR_ORDER(403, "배차받지 않은 주문이에요"),

    ORDER_NOT_FOUND(404, "주문을 찾을 수 없어요"),

    ALREADY_TAKEN(409, "이미 다른 분이 받았어요"),
    INVALID_STATE(409, "지금 상태에서는 할 수 없는 요청이에요"),

    OFFER_EXPIRED(410, "제안이 만료됐어요"),

    RATE_LIMITED(429, "요청이 너무 많아요. 잠시 뒤에 다시 시도해 주세요"),

    /** 표에는 없다. 우리가 예상 못 한 예외를 응답으로 바꿀 때만 쓴다. */
    INTERNAL_ERROR(500, "일시적인 오류가 발생했어요");

    private final int status;
    private final String defaultMessage;

    ErrorCode(int status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public int status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
