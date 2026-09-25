package com.delivery.notificationworker.kafka;

import com.delivery.common.JsonUtil;
import com.delivery.common.dispatch.KafkaOfferChannel;
import com.delivery.common.event.DispatchOffer;
import com.delivery.common.event.PushMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 2단계 실험: 래빗엠큐 OfferNotifyListener 의 카프카판. dispatch.offer 를 받아 푸시 토픽에 넣는다.
 *
 * <p>offer-relay 의 타이머와 같은 토픽을 읽지만 그룹이 달라서 서로 영향이 없다. 래빗엠큐에서
 * 익스체인지가 알림 큐와 타이머 큐로 복사해주던 걸 카프카에선 그룹 두 개가 대신한다.
 */
@Component
@RequiredArgsConstructor
public class KafkaOfferNotifyListener {

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${delivery.push.kafka-offer-topic:" + KafkaPushTopics.SHARED + "}")
    private String offerTopic;

    @KafkaListener(
            topics = KafkaOfferChannel.TOPIC_OFFER,
            groupId = "notification-worker",
            autoStartup = "#{'${delivery.offer.transport:rabbit}' == 'kafka'}")
    public void onOffer(String payload, Acknowledgment ack) {
        DispatchOffer offer = JsonUtil.fromJson(payload, DispatchOffer.class);
        kafkaTemplate.send(offerTopic, Long.toString(offer.riderId()), JsonUtil.toJson(PushMessage.offer(offer)));
        ack.acknowledge();
    }
}
