package com.delivery.common;

/**
 * 카프카 토픽 이름 상수.
 *
 * <p>토픽은 broker auto-create 를 끈 상태라 infra/create-topics.sh 로 명시 생성한다.
 * 여기 이름을 바꾸면 그 스크립트도 같이 바꿔야 한다 — 안 맞으면 컨슈머가 조용히 아무것도 못 받는다.
 */
public final class KafkaTopics {

    /** 라이더 위치 스트림. key = riderId (같은 라이더의 이동 순서 보장) */
    public static final String RIDER_LOCATION = "rider.location";

    /** 주문 생성. key = orderId */
    public static final String ORDER_CREATED = "order.created";

    /** 주문 상태 변경(수락/픽업/완료/취소). key = orderId */
    public static final String ORDER_STATUS = "order.status";

    /** 배차 확정. key = orderId */
    public static final String DISPATCH_ASSIGNED = "dispatch.assigned";

    /** 후보 소진으로 배차 실패. key = orderId */
    public static final String DISPATCH_FAILED = "dispatch.failed";

    /** 배달 완료 — 정산 입력. key = orderId */
    public static final String DELIVERY_COMPLETED = "delivery.completed";

    /** 컨슈머 재시도 소진분(Spring Kafka DLT 규약: 원본토픽 + ".DLT") */
    public static final String SUFFIX_DLT = ".DLT";

    private KafkaTopics() {
    }
}
