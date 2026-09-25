package com.delivery.notificationworker.push;

import com.delivery.common.RabbitTopology;
import com.delivery.common.event.PushMessage;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * NW-02 푸시 발송의 입구. PushDelivery 가 정한 결과를 ack 나 nack 으로 바꾸기만 한다.
 *
 * <p>DLQ 로 보낼 때 requeue=false 로 nack 한다. notify.push 에 DLX 가 걸려 있어서 그러면
 * notify.push.dlq 로 옮겨진다. requeue=true 로 하면 같은 메시지가 곧바로 다시 들어와서 끝없이 돈다.
 */
@Component
@RequiredArgsConstructor
public class PushListener {

    private final PushDelivery pushDelivery;

    @RabbitListener(queues = RabbitTopology.Q_PUSH,
            // 2단계 실험: transport=kafka 면 kafka 패키지의 리스너가 대신 받는다
            autoStartup = "#{'${delivery.offer.transport:rabbit}' == 'rabbit'}")
    public void onPush(PushMessage message, Channel channel,
                       @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        PushDelivery.Outcome outcome;
        try {
            outcome = pushDelivery.deliver(message);
        } catch (InterruptedException e) {
            // 종료 중이다. 이 메시지는 다른 워커가 가져가게 되돌려 놓는다.
            Thread.currentThread().interrupt();
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        switch (outcome) {
            case SENT, EXPIRED -> channel.basicAck(deliveryTag, false);
            case RATE_LIMITED, DEAD -> channel.basicNack(deliveryTag, false, false);
        }
    }
}
