package com.delivery.common.event;

/**
 * 가짜 푸시 웹훅 본문. notification-worker(NW-02)가 rider-simulator 의 POST /sim/push(SM-04)로 보낸다.
 *
 * <p>보내는 쪽과 받는 쪽이 다른 서비스라 여기 둔다. 각자 JSON 필드 이름을 적으면 한쪽이 바꿨을 때
 * 시뮬레이터가 offerId 를 0 으로 읽고, 수락 요청이 전부 404 가 나는데 원인이 안 보인다.
 *
 * @param storeName    기능 정의서엔 있지만 아직 채울 데가 없다. 주문에 가게 이름이 없고 storeId 만 있어서 null 이다
 * @param expiresInSec 보내는 순간 기준으로 남은 초. 큐에서 기다린 만큼 10초보다 짧다
 */
public record OfferPush(
        long riderId,
        long offerId,
        long orderId,
        String storeName,
        long expiresInSec
) {
}
