package com.delivery.common.dispatch;

import com.delivery.common.JsonUtil;
import com.delivery.common.event.DispatchOffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.TimeUnit;

/**
 * 2단계 실험: 제안을 카프카로 보낸다.
 *
 * <p>래빗엠큐는 익스체인지 하나에 넣으면 알림 큐와 타이머 큐로 브로커가 복사해줬다. 카프카는
 * 토픽 하나에 넣고 <b>컨슈머 그룹을 둘로</b> 나눠 읽으면 같은 일이 된다. notification-worker 는
 * 알림용으로, offer-relay 의 타이머는 10초를 세려고 각자 처음부터 끝까지 읽는다.
 *
 * <p>키는 orderId 다. 한 주문의 제안과 만료가 같은 파티션에 순서대로 쌓인다.
 */
public class KafkaOfferChannel implements OfferChannel {

    public static final String TOPIC_OFFER = "dispatch.offer";
    public static final String TOPIC_OFFER_EXPIRED = "dispatch.offer.expired";

    private static final Logger log = LoggerFactory.getLogger(KafkaOfferChannel.class);
    private static final long ACK_TIMEOUT_MS = 5_000;

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaOfferChannel(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public boolean send(DispatchOffer offer) {
        try {
            // 래빗엠큐 publisher confirm 과 같은 자리다. 브로커가 받았다고 할 때까지 기다린다.
            kafkaTemplate.send(TOPIC_OFFER, Long.toString(offer.orderId()), JsonUtil.toJson(offer))
                    .get(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("제안 발행(카프카) 실패: orderId={} riderId={}", offer.orderId(), offer.riderId(), e);
            return false;
        }
    }

    @Override
    public void expireNow(DispatchOffer offer) {
        kafkaTemplate.send(TOPIC_OFFER_EXPIRED, Long.toString(offer.orderId()), JsonUtil.toJson(offer));
    }
}
