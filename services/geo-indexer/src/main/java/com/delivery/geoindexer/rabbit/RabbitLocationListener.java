package com.delivery.geoindexer.rabbit;

import com.delivery.common.RabbitTopology;
import com.delivery.geoindexer.kafka.RiderLocationListener;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 2단계 실험: 래빗엠큐로 받은 좌표를 카프카 리스너와 같은 본문으로 넘긴다.
 *
 * <p>컨슈머 셋이 <b>한 큐를 나눠 먹는다.</b> 카프카는 라이더 한 명의 좌표가 늘 같은 파티션,
 * 같은 스레드로 가지만 여기는 브로커가 도는 순서대로 셋에게 번갈아 준다. 같은 라이더의 연속된
 * 좌표 두 개가 서로 다른 스레드에 들어가면 어느 쪽이 먼저 레디스에 쓸지 아무도 모른다.
 * 그게 얼마나 자주 일어나는지를 {@code geo_index_order_regression_total} 로 센다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "delivery.listener.rider-location.transport", havingValue = "rabbit")
public class RabbitLocationListener {

    private final RiderLocationListener delegate;

    @RabbitListener(queues = RabbitTopology.Q_RIDER_LOCATION, containerFactory = "locationBatchFactory")
    public void onLocations(List<Message> messages) {
        List<String> payloads = new ArrayList<>(messages.size());
        for (Message message : messages) {
            payloads.add(new String(message.getBody(), StandardCharsets.UTF_8));
        }
        delegate.handle(payloads);
    }
}
