package com.delivery.notificationworker.rabbit;

import com.delivery.common.RabbitTopology;
import com.delivery.common.event.DispatchOffer;
import com.delivery.common.event.PushMessage;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * NW-01 제안 알림 접수. dispatch.offer.notify 에서 꺼내 notify.push 에 priority 9 로 넣는다.
 *
 * <p>바로 보내지 않고 한 번 더 큐에 넣는 이유는 기능 정의서 NW-01 규칙 3번 그대로다. 발송하는 자리를
 * notify.push 소비자 하나로 모아야 레이트리밋을 한 군데서 건다. 그리고 마케팅(priority 1)과 같은 큐에
 * 섞여야 우선순위가 실제로 무슨 일을 하는지 보인다.
 */
@Slf4j
@Component
public class OfferNotifyListener {

    private final RabbitTemplate rabbitTemplate;
    private final Counter accepted;
    private final Counter dropped;

    public OfferNotifyListener(RabbitTemplate rabbitTemplate, MeterRegistry registry) {
        this.rabbitTemplate = rabbitTemplate;
        this.accepted = Counter.builder("push_offer_enqueued_total")
                .description("제안 알림을 notify.push 에 넣은 수").register(registry);
        this.dropped = Counter.builder("push_offer_dropped_total")
                .description("notify.push 에 못 넣고 버린 제안 알림 수").register(registry);
    }

    @RabbitListener(queues = RabbitTopology.Q_OFFER_NOTIFY,
            // 2단계 실험: transport=kafka 면 kafka 패키지의 리스너가 대신 받는다
            autoStartup = "#{'${delivery.offer.transport:rabbit}' == 'rabbit'}")
    public void onOffer(DispatchOffer offer, Channel channel,
                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            rabbitTemplate.convertAndSend(RabbitTopology.NOTIFY_EXCHANGE, RabbitTopology.RK_PUSH,
                    PushMessage.offer(offer), message -> {
                        message.getMessageProperties().setPriority(RabbitTopology.PRIORITY_OFFER);
                        return message;
                    });
            accepted.increment();
            channel.basicAck(deliveryTag, false);
        } catch (AmqpException e) {
            // requeue 없이 버린다. 되돌려 넣으면 브로커가 안 좋은 동안 같은 메시지가 초당 수천 번 돈다.
            // 버려도 배차는 안 멈춘다 — 타이머 큐가 10초 뒤 만료시켜서 RE-02 가 다음 후보로 넘긴다.
            // 라이더 한 명이 푸시를 못 받은 것과 같은 결과다.
            dropped.increment();
            log.error("제안 알림을 notify.push 에 못 넣어서 버린다: offerId={} cause={}", offer.offerId(), e.toString());
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
