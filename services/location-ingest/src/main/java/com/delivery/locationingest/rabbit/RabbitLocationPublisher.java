package com.delivery.locationingest.rabbit;

import com.delivery.common.JsonUtil;
import com.delivery.common.RabbitTopology;
import com.delivery.common.event.RiderLocation;
import com.delivery.locationingest.ingest.LocationSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 2단계 실험: rider.location 을 카프카 대신 래빗엠큐 큐에 넣는다.
 *
 * <p>카프카 쪽({@code acks=1}, 콜백으로 실패만 센다)과 맞추려고 publisher confirm 을 안 켜고
 * 메시지도 non-persistent 로 보낸다. 위치는 잃어도 되니까 디스크에 fsync 할 이유가 없다.
 *
 * <p>문자열을 {@code convertAndSend} 로 넘기면 안 된다. common 이 JSON 컨버터를 깔아둬서
 * 문자열이 한 번 더 JSON 으로 감싸져 {@code "{\"riderId\":...}"} 로 나간다. 바이트로 직접 만든다.
 *
 * <p>브로커가 메모리나 디스크 알람을 올리면 이 연결을 막는다(connection.blocked). 그때
 * {@code send()} 는 예외를 안 던지고 <b>그 자리에서 멈춘다.</b> 카프카의 {@code max.block.ms}
 * 같은 상한이 없어서, 톰캣 스레드가 여기 줄줄이 묶인다. 실험에서 보려는 게 바로 그 장면이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "delivery.ingest.transport", havingValue = "rabbit")
public class RabbitLocationPublisher implements LocationSink {

    private final RabbitTemplate rabbitTemplate;
    private final Counter failed;

    public RabbitLocationPublisher(RabbitTemplate rabbitTemplate, MeterRegistry meterRegistry) {
        this.rabbitTemplate = rabbitTemplate;
        this.failed = Counter.builder("location_ingest_publish_failed_total")
                .description("rider.location 발행 실패 수. 라이더 앱에는 202 가 나갔다")
                .register(meterRegistry);
    }

    @Override
    public void publish(RiderLocation location, Runnable onFailure) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT);
        Message message = new Message(JsonUtil.toJson(location).getBytes(StandardCharsets.UTF_8), props);
        try {
            // 기본 익스체인지("")에 큐 이름을 라우팅 키로 주면 그 큐로 바로 간다
            rabbitTemplate.send("", RabbitTopology.Q_RIDER_LOCATION, message);
        } catch (RuntimeException e) {
            failed.increment();
            onFailure.run();
            log.warn("rider.location(래빗) 발행 실패, 버린다: riderId={} cause={}", location.riderId(), e.toString());
        }
    }
}
