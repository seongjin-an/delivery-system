package com.delivery.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 카프카/래빗엠큐 페이로드를 문자열로 주고받기 때문에 직렬화를 한 곳에 모아둔다.
 * 서비스마다 ObjectMapper 를 따로 만들면 어떤 놈은 타임스탬프를 숫자로, 어떤 놈은 ISO 로 써서 깨진다.
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // 이벤트에 필드가 추가돼도 구버전 컨슈머가 죽지 않게 — 무중단 배포의 최소 조건
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("직렬화 실패: " + value.getClass().getSimpleName(), e);
        }
    }

    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("역직렬화 실패: " + type.getSimpleName() + " ← " + json, e);
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    private JsonUtil() {
    }
}
