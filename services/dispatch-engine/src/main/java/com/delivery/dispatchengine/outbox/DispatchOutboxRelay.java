package com.delivery.dispatchengine.outbox;

import com.delivery.common.RedisKeys;
import com.delivery.common.dispatch.DispatchEventPublisher;
import com.delivery.common.dispatch.OutboxEnvelope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisListCommands.Direction;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 레디스 아웃박스를 비우며 카프카로 보낸다.
 *
 * <p>수락 Lua 가 보드를 ACCEPTED 로 바꾸면서 {@code dispatch:outbox} 에 이벤트를 같이 넣는다. 여기서는 그걸
 * 하나씩 {@code dispatch:outbox:inflight} 로 옮기고(LMOVE, 한 번에 되니 인스턴스가 여럿이어도 같은 걸 둘이
 * 안 집는다), 카프카가 받았다고 하면 inflight 에서 지운다.
 *
 * <p><b>보내다 죽으면 inflight 에 남는다.</b> 그래서 {@link #recover} 가 오래 남은 걸 다시 보낸다. 같은 이벤트가
 * 두 번 갈 수 있는데 괜찮다. order-api 의 OR-07 은 CREATED, DISPATCHING 일 때만 ASSIGNED 로 바꿔서 두 번째는
 * 아무 일도 안 한다. 대신 한 번도 안 가는 일은 없다. 수락이 레디스에 적혔으면 이벤트도 레디스에 있기 때문이다.
 *
 * <p><b>한 판에 여러 건을 먼저 다 보내놓고 한꺼번에 기다린다.</b> 처음엔 한 건씩 보내고 기다렸는데, 카프카 응답이
 * 한 번에 수십~수백 ms 라 초당 30건(수락 한 건에 두 개)을 못 따라갔다. 배차 확정이 최대 6.4초 늦게 나갔고,
 * 수락 5초 뒤 픽업하러 온 라이더가 "아직 배차 안 됐다(INVALID_STATE)" 를 받았다. 한 판에 45건.
 *
 * <p>실패가 하나라도 나면 이번 판은 거기서 멈춘다. 그 묶음(최대 100건)은 이미 inflight 로 옮겨져서 30초 뒤
 * {@link #recover} 가 다시 보낸다. 그 뒤 것들은 outbox 에 그대로 있다가 카프카가 살아나면 다음 판에 바로 나간다.
 * 멈추지 않고 계속 꺼내면 전부 inflight 로 옮겨져서 되살아났을 때 다 같이 30초를 더 기다려야 한다.
 */
public class DispatchOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(DispatchOutboxRelay.class);

    /** 한 번에 꺼내서 몰아 보내는 최대 건수 */
    private static final int BATCH = 100;
    /** 한 판에 최대 몇 번 묶음을 보낼지. 밀려 있어도 스케줄러 스레드를 너무 오래 잡지 않게 */
    private static final int MAX_BATCHES_PER_DRAIN = 5;

    private final StringRedisTemplate redis;
    private final DispatchEventPublisher publisher;
    private final String outboxKey;
    private final String inflightKey;
    private final Duration stuckAfter;
    private final Clock clock;
    private final Counter sent;
    private final Counter failed;
    private final Counter resent;

    public DispatchOutboxRelay(StringRedisTemplate redis, DispatchEventPublisher publisher,
                               String outboxKey, String inflightKey, Duration stuckAfter,
                               Clock clock, MeterRegistry registry) {
        this.redis = redis;
        this.publisher = publisher;
        this.outboxKey = outboxKey;
        this.inflightKey = inflightKey;
        this.stuckAfter = stuckAfter;
        this.clock = clock;
        this.sent = Counter.builder("dispatch_outbox_sent_total")
                .description("레디스 아웃박스에서 꺼내 카프카로 보낸 이벤트 수").register(registry);
        this.failed = Counter.builder("dispatch_outbox_failed_total")
                .description("보내다 실패한 수. inflight 에 남았다가 다시 보낸다").register(registry);
        this.resent = Counter.builder("dispatch_outbox_resent_total")
                .description("inflight 에 오래 남아 다시 보낸 수. 두 번 갔을 수 있다").register(registry);
    }

    public static DispatchOutboxRelay withDefaultKeys(StringRedisTemplate redis, DispatchEventPublisher publisher,
                                                      Duration stuckAfter, Clock clock, MeterRegistry registry) {
        return new DispatchOutboxRelay(redis, publisher, RedisKeys.DISPATCH_OUTBOX,
                RedisKeys.DISPATCH_OUTBOX_INFLIGHT, stuckAfter, clock, registry);
    }

    /** @return 이번 판에 보낸 수 */
    @Scheduled(fixedDelayString = "${delivery.dispatch-outbox.drain-interval}")
    public int drain() {
        ListOperations<String, String> list = redis.opsForList();
        int count = 0;
        for (int round = 0; round < MAX_BATCHES_PER_DRAIN; round++) {
            List<String> items = new ArrayList<>(BATCH);
            String item;
            while (items.size() < BATCH
                    && (item = list.move(outboxKey, Direction.LEFT, inflightKey, Direction.RIGHT)) != null) {
                items.add(item);
            }
            if (items.isEmpty()) {
                break;
            }
            int done = sendAll(items);
            count += done;
            if (done < items.size()) {
                break;    // 하나라도 실패했다. 카프카가 이상하니 이번 판은 여기서 멈춘다
            }
            if (items.size() < BATCH) {
                break;    // 다 비웠다
            }
        }
        return count;
    }

    /**
     * 먼저 다 보내놓고 순서대로 기다린다. 받았다고 한 것만 inflight 에서 지운다.
     *
     * @return 보낸 수. 실패한 게 있으면 그 앞까지만 센다(뒤엣것도 성공했으면 지운다. 다음 판에 또 보낼 필요는 없다)
     */
    private int sendAll(List<String> items) {
        List<CompletableFuture<?>> futures = new ArrayList<>(items.size());
        List<Boolean> readable = new ArrayList<>(items.size());
        for (String item : items) {
            OutboxEnvelope envelope = parse(item);
            readable.add(envelope != null);
            futures.add(envelope == null ? CompletableFuture.completedFuture(null) : sendQuietly(envelope));
        }
        ListOperations<String, String> list = redis.opsForList();
        int ok = 0;
        boolean allOk = true;
        for (int i = 0; i < items.size(); i++) {
            if (await(futures.get(i), items.get(i))) {
                list.remove(inflightKey, 1, items.get(i));
                if (readable.get(i)) {
                    sent.increment();
                }
                if (allOk) {
                    ok++;
                }
            } else {
                allOk = false;
            }
        }
        return ok;
    }

    /** @return 다시 보낸 수 */
    @Scheduled(fixedDelayString = "${delivery.dispatch-outbox.recover-interval}")
    public int recover() {
        ListOperations<String, String> list = redis.opsForList();
        List<String> items = list.range(inflightKey, 0, -1);
        if (items == null || items.isEmpty()) {
            return 0;
        }
        long cutoff = clock.millis() - stuckAfter.toMillis();
        int count = 0;
        for (String item : items) {
            if (enqueuedAt(item) > cutoff) {
                // 지금 누가 보내는 중일 수 있다. 조금 더 기다린다
                continue;
            }
            if (!trySend(item)) {
                break;
            }
            list.remove(inflightKey, 1, item);
            resent.increment();
            count++;
        }
        if (count > 0) {
            log.warn("아웃박스 inflight 에 {}초 넘게 남은 이벤트 {}건을 다시 보냈다. 보내다 죽은 인스턴스가 있었다",
                    stuckAfter.toSeconds(), count);
        }
        return count;
    }

    private boolean trySend(String item) {
        OutboxEnvelope envelope = parse(item);
        if (envelope == null) {
            redis.opsForList().remove(inflightKey, 1, item);
            return true;
        }
        return await(sendQuietly(envelope), item);
    }

    /** 못 읽는 건 null. 몇 번을 다시 해도 못 읽으니 버린다. 남겨두면 recover 가 영원히 붙잡고 있어서 뒤엣것까지 막는다 */
    private OutboxEnvelope parse(String item) {
        try {
            return OutboxEnvelope.fromJson(item);
        } catch (Exception e) {
            log.error("아웃박스 항목을 못 읽어서 버린다: {}", item, e);
            return null;
        }
    }

    private CompletableFuture<?> sendQuietly(OutboxEnvelope envelope) {
        try {
            return publisher.sendAsync(envelope);
        } catch (Exception e) {
            // 브로커 메타데이터를 못 받으면 send() 가 그 자리에서 터진다
            return CompletableFuture.failedFuture(e);
        }
    }

    private boolean await(CompletableFuture<?> future, String item) {
        try {
            future.get(DispatchEventPublisher.sendTimeoutSeconds(), TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            failed.increment();
            log.warn("아웃박스 이벤트를 못 보냈다. inflight 에 남겨두고 다음에 다시 보낸다: {} cause={}", abbreviate(item), e.toString());
            return false;
        }
    }

    private static String abbreviate(String item) {
        return item.length() <= 160 ? item : item.substring(0, 160) + "...";
    }

    private static long enqueuedAt(String item) {
        try {
            return OutboxEnvelope.fromJson(item).enqueuedAt();
        } catch (Exception e) {
            return 0;
        }
    }
}
