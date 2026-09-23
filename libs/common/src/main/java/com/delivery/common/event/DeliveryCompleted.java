package com.delivery.common.event;

import java.time.Instant;

/**
 * delivery.completed 토픽 페이로드. 배달이 끝났다 (OR-04).
 *
 * <p>order-api 가 주문 행을 DELIVERED 로 바꾸는 트랜잭션 안에서 아웃박스에 넣는다.
 * settlement-service 가 이걸로 라이더별 일일 정산을 낸다(SE-01). 정산이 주문 DB 를 다시 읽지 않게
 * 필요한 값은 여기 다 싣는다. 금액과 거리는 접수 때 저장한 값 그대로라, 나중에 계산식이 바뀌어도
 * 7일치를 다시 흘렸을 때 같은 정산이 나온다.
 *
 * @param assignedAt     라이더가 수락한 시각 (OR-07 이 timeline 에 남긴 ASSIGNED)
 * @param elapsedSeconds 수락부터 완료까지. 기능 정의서엔 뜻이 안 적혀 있어서 "배달 한 건에 걸린 시간" 으로 정했다.
 *                       접수부터 세면 배차가 늦은 게 라이더 탓처럼 섞인다
 */
public record DeliveryCompleted(
        long orderId,
        long riderId,
        String zoneId,
        int priceKrw,
        int distanceMeters,
        Instant assignedAt,
        Instant completedAt,
        long elapsedSeconds
) {
}
