package com.delivery.notificationworker.push;

import com.delivery.common.RabbitTopology;
import com.delivery.common.event.PushMessage;
import com.delivery.notificationworker.config.PushProperties;
import com.delivery.notificationworker.ratelimit.TokenBucket;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * NW-02 한 건을 어떻게 끝낼지 정한다. 결과만 돌려주고 ack 는 리스너가 한다.
 *
 * <p>순서: 이미 만료됐나 → 토큰 → 보내기 → 실패하면 쉬었다가 처음부터. 재시도마다 토큰을 새로 받는다.
 * 외부 API 입장에선 재시도도 한 번의 요청이라, 안 그러면 실패가 몰릴 때 한도를 넘겨 보낸다.
 */
@Slf4j
@Component
public class PushDelivery {

    /**
     * 남은 시간이 이보다 짧으면 안 보낸다. 라이더가 알림을 보고 누르는 데 1초는 걸린다.
     * 0.6초 남은 제안을 보내봐야 수락은 410 이고, 외부 API 한도만 하나 쓴다.
     */
    static final Duration MIN_TIME_LEFT = Duration.ofSeconds(1);

    public enum Outcome {
        /** 보냈다. ack */
        SENT,
        /** 큐에서 기다리다 제안이 끝났다. 보낼 의미가 없다. ack */
        EXPIRED,
        /** 토큰을 끝내 못 받았다. DLQ 로 */
        RATE_LIMITED,
        /** 재시도를 다 썼다. DLQ 로 */
        DEAD
    }

    private final PushSender sender;
    private final TokenBucket tokenBucket;
    private final PushProperties properties;
    private final Clock clock;

    private final Map<PushMessage.Kind, Counter> sent = new EnumMap<>(PushMessage.Kind.class);
    private final Map<PushMessage.Kind, Counter> failed = new EnumMap<>(PushMessage.Kind.class);
    private final Map<PushMessage.Kind, Counter> dead = new EnumMap<>(PushMessage.Kind.class);
    private final Map<PushMessage.Kind, Counter> rateLimited = new EnumMap<>(PushMessage.Kind.class);
    private final Counter expired;
    private final Timer offerAge;

    public PushDelivery(PushSender sender, TokenBucket tokenBucket, PushProperties properties,
                        Clock clock, MeterRegistry registry) {
        this.sender = sender;
        this.tokenBucket = tokenBucket;
        this.properties = properties;
        this.clock = clock;
        for (PushMessage.Kind kind : PushMessage.Kind.values()) {
            String tag = kind.name().toLowerCase();
            sent.put(kind, counter(registry, "push_sent_total", "보낸 수", tag));
            failed.put(kind, counter(registry, "push_failed_total", "한 번 보내다 실패한 수 (재시도 포함)", tag));
            dead.put(kind, counter(registry, "push_dead_total", "재시도를 다 쓰고 DLQ 로 간 수", tag));
            rateLimited.put(kind, counter(registry, "push_ratelimited_total", "토큰을 못 받아 DLQ 로 간 수", tag));
        }
        this.expired = Counter.builder("push_expired_total")
                .description("큐에서 기다리다 제안 유효시간이 끝나서 안 보낸 수").register(registry);
        // 시나리오 C 에서 제일 먼저 볼 숫자. 큐 깊이는 "얼마나 밀렸나" 고, 이건 "라이더가 몇 초를 잃었나" 다.
        this.offerAge = Timer.builder("push_offer_age")
                .description("제안이 나간 뒤 푸시가 실제로 나가기까지 걸린 시간").register(registry);
    }

    private static Counter counter(MeterRegistry registry, String name, String description, String kind) {
        return Counter.builder(name).description(description).tag("kind", kind).register(registry);
    }

    public Outcome deliver(PushMessage message) throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            Duration timeLeft = timeLeft(message);
            if (timeLeft.compareTo(MIN_TIME_LEFT) < 0) {
                expired.increment();
                log.info("제안이 큐에서 기다리다 끝나서 안 보낸다: offerId={} 남은시간={}ms", message.offerId(), timeLeft.toMillis());
                return Outcome.EXPIRED;
            }
            if (!tokenBucket.acquireWithWait()) {
                rateLimited.get(message.kind()).increment();
                log.warn("푸시 한도에 걸려 DLQ 로 보낸다: kind={} offerId={}", message.kind(), message.offerId());
                return Outcome.RATE_LIMITED;
            }
            try {
                sender.send(message, timeLeft.toSeconds());
                sent.get(message.kind()).increment();
                if (message.kind() == PushMessage.Kind.OFFER) {
                    offerAge.record(Duration.between(message.offeredAt(), clock.instant()));
                }
                return Outcome.SENT;
            } catch (PushFailedException e) {
                failed.get(message.kind()).increment();
                if (attempt >= properties.maxAttempts()) {
                    dead.get(message.kind()).increment();
                    log.warn("푸시를 {}번 다 실패해서 DLQ 로 보낸다: kind={} offerId={} cause={}",
                            attempt, message.kind(), message.offerId(), e.getMessage());
                    return Outcome.DEAD;
                }
                Thread.sleep(properties.retryBackoff().toMillis() << (attempt - 1));
            }
        }
    }

    /** 제안이면 10초에서 흐른 시간을 뺀다. 마케팅은 끝이 없다 */
    private Duration timeLeft(PushMessage message) {
        if (message.kind() != PushMessage.Kind.OFFER || message.offeredAt() == null) {
            return Duration.ofDays(1);
        }
        Duration elapsed = Duration.between(message.offeredAt(), clock.instant());
        return Duration.ofMillis(RabbitTopology.OFFER_TTL_MS).minus(elapsed);
    }
}
