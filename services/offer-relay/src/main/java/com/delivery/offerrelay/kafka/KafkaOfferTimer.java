package com.delivery.offerrelay.kafka;

import com.delivery.common.JsonUtil;
import com.delivery.common.RabbitTopology;
import com.delivery.common.dispatch.KafkaOfferChannel;
import com.delivery.common.event.DispatchOffer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 2단계 실험: 래빗엠큐 타이머 큐(TTL 10초 + DLX)를 카프카로 손수 만든 것.
 *
 * <p>{@code dispatch.offer} 를 자기 그룹({@code offer-timer})으로 처음부터 읽는다. 레코드마다
 * {@code offeredAt + 10초} 가 될 때까지 기다렸다가 {@code dispatch.offer.expired} 로 넘기고 ack 한다.
 *
 * <p><b>왜 기다리기만 하면 되나.</b> TTL 이 전부 10초로 같아서다. 한 파티션 안에서는 먼저 들어온
 * 제안이 먼저 만료된다. 맨 앞 하나를 기다리는 동안 뒤엣것들도 거의 다 제 차례가 온다.
 * 기다리는 스레드는 제안 수가 아니라 <b>파티션 수</b>만큼이다. 초당 200건이어도 6개다.
 *
 * <p><b>재시작해도 안 날아간다.</b> 넘긴 다음에 ack 하니까, 기다리던 중에 죽으면 그 레코드는
 * 커밋이 안 된 채라 다음에 뜬 쪽이 다시 읽는다. 이미 지난 시각이면 기다리지 않고 바로 넘긴다.
 * 넘기고 ack 하기 전에 죽으면 같은 만료가 두 번 가는데, 펜싱(offerId 비교)이 뒤엣것을 버린다.
 *
 * <p><b>TTL 이 제각각이면 이게 안 된다.</b> 맨 앞이 20초짜리면 그 뒤의 10초짜리들이 10초 늦는다.
 * 그땐 TTL 마다 토픽을 따로 둬야 한다. 래빗엠큐도 메시지마다 TTL 을 주면 같은 문제가 있어서
 * (맨 앞 메시지만 만료를 본다) 큐 단위 TTL 을 쓴 거다.
 */
@Component
public class KafkaOfferTimer {

    private static final Logger log = LoggerFactory.getLogger(KafkaOfferTimer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter forwarded;

    public KafkaOfferTimer(KafkaTemplate<String, String> kafkaTemplate, MeterRegistry registry) {
        this.kafkaTemplate = kafkaTemplate;
        this.forwarded = Counter.builder("offer_timer_forwarded_total")
                .description("10초를 기다려 dispatch.offer.expired 로 넘긴 수").register(registry);
    }

    @KafkaListener(
            topics = KafkaOfferChannel.TOPIC_OFFER,
            groupId = "offer-timer",
            concurrency = "${delivery.offer.timer-concurrency:6}",
            autoStartup = "#{'${delivery.offer.transport:rabbit}' == 'kafka'}")
    public void onOffer(ConsumerRecord<String, String> record, Acknowledgment ack) throws Exception {
        DispatchOffer offer = JsonUtil.fromJson(record.value(), DispatchOffer.class);
        long dueAt = offer.offeredAt().toEpochMilli() + RabbitTopology.OFFER_TTL_MS;
        long wait = dueAt - System.currentTimeMillis();
        if (wait > 0) {
            // 여기서 자는 동안 이 파티션은 멈춘다. 그래도 된다 — 뒤엣것들은 이것보다 늦게 만료된다.
            // max.poll.interval.ms(기본 5분)보다 훨씬 짧아서 그룹에서 쫓겨날 일도 없다.
            Thread.sleep(wait);
        }
        // 넘긴 게 확인된 다음에 ack 한다. 순서를 바꾸면 ack 하고 넘기기 전에 죽었을 때 만료가 사라진다.
        kafkaTemplate.send(KafkaOfferChannel.TOPIC_OFFER_EXPIRED, record.key(), record.value())
                .get(5, TimeUnit.SECONDS);
        forwarded.increment();
        ack.acknowledge();
        log.debug("만료 넘김: orderId={} offerId={} 늦음={}ms", offer.orderId(), offer.offerId(), -wait);
    }
}
