package com.delivery.common;

import com.github.f4b6a3.uuid.UuidCreator;

/**
 * orderId, riderId, offerId 를 만드는 자리. 기능 정의서 3.1.
 *
 * <p>UUIDv7 을 쓴다. 랜덤 UUID(v4) 와 달리 앞쪽에 시각이 들어 있어서 문자열로 정렬하면
 * 만들어진 순서대로 늘어선다. 로그를 눈으로 훑을 때 이게 생각보다 크게 편하다 —
 * 주문 아이디만 보고도 어느 게 먼저 들어온 건지 알 수 있다.
 *
 * <p>DB 인덱스에도 유리하다. v4 는 값이 사방에 흩어져서 B-트리 페이지를 계속 쪼개는데,
 * v7 은 뒤쪽에만 붙으니까 그 일이 안 생긴다.
 */
public final class Ids {

    public static String newId() {
        return UuidCreator.getTimeOrderedEpoch().toString();
    }

    private Ids() {
    }
}
