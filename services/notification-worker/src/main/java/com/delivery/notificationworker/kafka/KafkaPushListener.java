package com.delivery.notificationworker.kafka;

import com.delivery.common.JsonUtil;
import com.delivery.common.event.PushMessage;
import com.delivery.notificationworker.push.PushDelivery;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 2단계 실험: 래빗엠큐 PushListener 의 카프카판.
 *
 * <p>DLQ 가 없다. RATE_LIMITED 와 DEAD 도 ack 하고 넘어간다(지표는 PushDelivery 가 이미 셌다).
 * 이 실험에서 보려는 건 "제안이 몇 초 만에 나가나" 라서 거기까지는 안 만들었다.
 *
 * <p>concurrency 16 은 래빗엠큐 쪽 max-concurrency 16 에 맞춘 것이다. 카프카는 파티션 하나를
 * 스레드 하나만 읽으니 토픽도 16 파티션으로 만들어야 16 개가 다 일한다.
 */
@Component
@RequiredArgsConstructor
public class KafkaPushListener {

    private final PushDelivery pushDelivery;

    /** 마케팅은 늘 여기로 온다. 제안도 kafka-offer-topic 이 이것과 같으면 여기 섞인다 */
    @KafkaListener(
            topics = KafkaPushTopics.SHARED,
            groupId = "notification-push",
            concurrency = "16",
            autoStartup = "#{'${delivery.offer.transport:rabbit}' == 'kafka'}")
    public void onShared(String payload, Acknowledgment ack) throws InterruptedException {
        deliver(payload, ack);
    }

    /** 제안 전용 토픽을 따로 쓸 때만 뜬다. 마케팅과 컨슈머를 나눠서 줄을 따로 세운다 */
    @KafkaListener(
            topics = KafkaPushTopics.OFFER_ONLY,
            groupId = "notification-push-offer",
            concurrency = "16",
            autoStartup = "#{'${delivery.offer.transport:rabbit}' == 'kafka' "
                    + "and '${delivery.push.kafka-offer-topic:notify.push}' == 'notify.push.offer'}")
    public void onOfferOnly(String payload, Acknowledgment ack) throws InterruptedException {
        deliver(payload, ack);
    }

    private void deliver(String payload, Acknowledgment ack) throws InterruptedException {
        pushDelivery.deliver(JsonUtil.fromJson(payload, PushMessage.class));
        ack.acknowledge();
    }
}
