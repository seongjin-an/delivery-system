package com.delivery.offerrelay.kafka;

import com.delivery.common.JsonUtil;
import com.delivery.common.dispatch.KafkaOfferChannel;
import com.delivery.common.event.DispatchOffer;
import com.delivery.offerrelay.relay.OfferRelayService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 2단계 실험: 래빗엠큐 ExpiredOfferListener 의 카프카판. 할 일은 똑같다.
 *
 * <p>예외를 잡지 않는다. common 의 컨슈머 에러 핸들러가 3회 백오프 뒤 DLT 로 보낸다.
 */
@Component
@RequiredArgsConstructor
public class KafkaExpiredOfferListener {

    private final OfferRelayService relayService;

    @KafkaListener(
            topics = KafkaOfferChannel.TOPIC_OFFER_EXPIRED,
            groupId = "offer-relay",
            concurrency = "${delivery.offer.timer-concurrency:6}",
            autoStartup = "#{'${delivery.offer.transport:rabbit}' == 'kafka'}")
    public void onExpired(String payload, Acknowledgment ack) {
        relayService.relay(JsonUtil.fromJson(payload, DispatchOffer.class));
        ack.acknowledge();
    }
}
