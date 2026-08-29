package com.delivery.common.web;

import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import com.delivery.common.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 예외를 기능 정의서 3.6 의 에러 코드로 바꿔주는 한 곳.
 *
 * <p>서비스마다 try-catch 로 상태 코드를 정하면 같은 상황에 400 을 주는 서비스와 500 을 주는
 * 서비스가 생긴다. 그러면 시뮬레이터가 응답을 보고 다음 동작을 못 고른다.
 * 상태 코드를 정하는 자리를 여기 하나로 몰아두는 게 목적이다.
 *
 * <p>이 클래스는 com.delivery.common 아래에 있어서 서비스의 컴포넌트 스캔에 안 걸린다.
 * CommonWebAutoConfiguration 이 자동설정으로 올려준다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 우리가 의도해서 던진 실패. 스택트레이스까지 남길 이유가 없어서 한 줄만 찍는다 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.warn("business: {} - {}", e.errorCode(), e.getMessage());
        return respond(e.errorCode(), e.getMessage());
    }

    /** @Valid 로 걸러진 요청 본문. 어느 필드가 왜 틀렸는지까지 알려줘야 http 파일로 고치기 쉽다 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleBodyValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .reduce((a, b) -> a + ", " + b)
                .orElse(ErrorCode.INVALID_REQUEST.defaultMessage());
        log.warn("invalid body: {}", detail);
        return respond(ErrorCode.INVALID_REQUEST, detail);
    }

    /**
     * 헤더가 없을 때. Idempotency-Key 만 따로 코드를 나눈다.
     * 시뮬레이터가 "헤더를 빼먹은 건지 값이 틀린 건지" 를 응답만 보고 알 수 있어야 해서다.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException e) {
        ErrorCode code = CommonHeaders.IDEMPOTENCY_KEY.equalsIgnoreCase(e.getHeaderName())
                ? ErrorCode.MISSING_IDEMPOTENCY_KEY
                : ErrorCode.INVALID_REQUEST;
        log.warn("missing header: {}", e.getHeaderName());
        return respond(code, "%s 헤더가 필요해요".formatted(e.getHeaderName()));
    }

    /** 나머지 형식 오류 묶음 — 파라미터 누락, 타입 안 맞음, JSON 파싱 실패, 파라미터 검증 실패 */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            HandlerMethodValidationException.class,
            ConstraintViolationException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleMalformed(Exception e) {
        log.warn("invalid request: {}", e.getMessage());
        return respond(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.defaultMessage());
    }

    /**
     * 여기까지 온 건 우리가 예상 못 한 것뿐이다. 그러니 스택트레이스를 통째로 남긴다.
     *
     * <p>대신 응답에는 예외 메시지를 안 싣는다. SQL 문이나 내부 호스트 이름이 그대로 나가는 수가 있다.
     * 자세한 건 로그에서 trace_id 로 찾는다 — 3단계에서 로키·템포를 붙이는 이유가 이거다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("예상 못 한 예외", e);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage());
    }

    private static ResponseEntity<ApiResponse<Void>> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.status()).body(ApiResponse.fail(code, message));
    }

    private static String describe(FieldError error) {
        return "%s: %s".formatted(error.getField(), error.getDefaultMessage());
    }
}
