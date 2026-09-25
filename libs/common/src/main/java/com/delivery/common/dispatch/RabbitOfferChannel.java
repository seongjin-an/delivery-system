package com.delivery.common.dispatch;

import com.delivery.common.RabbitTopology;
import com.delivery.common.event.DispatchOffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 래빗엠큐로 제안을 보낸다. 원래 OfferSender 안에 있던 코드를 그대로 옮긴 것이다.
 */
public class RabbitOfferChannel implements OfferChannel {

    private static final Logger log = LoggerFactory.getLogger(RabbitOfferChannel.class);

    /** publisher confirm 을 기다리는 한도 */
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final RabbitTemplate rabbitTemplate;

    public RabbitOfferChannel(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public boolean send(DispatchOffer offer) {
        requirePublisherConfirms();
        CorrelationData confirm = new CorrelationData(Long.toString(offer.offerId()));

        // 익스체인지에 한 번만 발행한다. 알림 큐와 타이머 큐 양쪽에 브로커가 복제해준다.
        // 코드에서 두 번 발행하면 한쪽만 성공하는 경우가 생기는데, 그게 둘 다 사고다 —
        // 알림만 가면 안 받았을 때 아무도 모르고, 타이머만 있으면 라이더는 제안이 온 줄도
        // 모르는데 10초 뒤에 거절한 걸로 처리된다.
        rabbitTemplate.convertAndSend(
                RabbitTopology.DISPATCH_EXCHANGE, RabbitTopology.RK_OFFER_CREATED, offer, confirm);
        return confirmed(confirm, offer);
    }

    @Override
    public void expireNow(DispatchOffer offer) {
        rabbitTemplate.convertAndSend(RabbitTopology.DISPATCH_DLX, RabbitTopology.RK_OFFER_EXPIRED, offer);
    }

    /**
     * publisher confirm 이 꺼져 있으면 여기서 바로 끝낸다.
     *
     * <p>실제로 한 번 당했다. dispatch-engine yml 에만 {@code publisher-confirm-type: correlated}
     * 가 있고 offer-relay 에는 없었는데, 이 클래스를 공용으로 옮기면서 그 전제가 같이 안 따라왔다.
     * 증상이 고약했다 — 확인 future 가 영영 안 끝나서 후보 한 명당 5초씩 타임아웃이 나고,
     * 실패로 판정해 다음 후보로 넘어간다. 그래서 라이더 6명이 25초 만에 전부 타버리고
     * 배차가 실패했다. 로그에는 "제안 발행 확인 실패" 만 찍혀서 브로커 문제처럼 보인다.
     */
    private void requirePublisherConfirms() {
        boolean correlated =
                rabbitTemplate.getConnectionFactory() instanceof CachingConnectionFactory caching
                        && caching.isPublisherConfirms();
        if (correlated) {
            return;
        }
        throw new IllegalStateException(
                "publisher confirm 이 꺼져 있어서 제안을 보낼 수 없다. "
                        + "이 서비스의 application.yml 에 spring.rabbitmq.publisher-confirm-type=correlated 를 넣어야 한다. "
                        + "안 넣으면 확인 응답을 영영 못 받아서 제안마다 " + CONFIRM_TIMEOUT.toSeconds() + "초씩 버리고 실패한다.");
    }

    private boolean confirmed(CorrelationData confirm, DispatchOffer offer) {
        try {
            CorrelationData.Confirm result =
                    confirm.getFuture().get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (result != null && result.isAck()) {
                return true;
            }
            log.error("제안 발행을 브로커가 거절했다: orderId={} riderId={} 이유={}",
                    offer.orderId(), offer.riderId(), result == null ? "응답 없음" : result.getReason());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("제안 발행 확인 실패: orderId={} riderId={}", offer.orderId(), offer.riderId(), e);
            return false;
        }
    }
}
