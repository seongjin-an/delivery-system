package com.delivery.notificationworker.kafka;

/**
 * 2단계 실험: 카프카로 푸시를 돌릴 때 쓰는 토픽.
 *
 * <p>카프카엔 우선순위가 없다. 그래서 두 가지로 돌려본다.
 * <ul>
 *   <li>{@code delivery.push.kafka-offer-topic=notify.push} — 제안과 마케팅을 한 토픽에 섞는다.
 *       래빗엠큐의 notify.push 와 같은 모양인데 priority 만 빠진 것이다.</li>
 *   <li>{@code delivery.push.kafka-offer-topic=notify.push.offer} — 제안만 따로 받는 토픽과 컨슈머를 둔다.
 *       카프카에서 우선순위가 필요할 때 보통 이렇게 한다.</li>
 * </ul>
 */
public final class KafkaPushTopics {

    public static final String SHARED = "notify.push";
    public static final String OFFER_ONLY = "notify.push.offer";

    private KafkaPushTopics() {
    }
}
