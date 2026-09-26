package com.delivery.dispatchengine;

import com.delivery.common.Ids;
import com.delivery.common.dispatch.DispatchEventPublisher;
import com.delivery.common.dispatch.OutboxEnvelope;
import com.delivery.dispatchengine.outbox.DispatchOutboxRelay;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 레디스 아웃박스 릴레이를 진짜 레디스에 돌려본다. 키는 판마다 새로 뗀다.
 * 진짜 dispatch:outbox 를 쓰면 떠 있는 dispatch-engine 이 먼저 꺼내간다.
 */
class DispatchOutboxRelayRedisTest {

    private static final String HOST = "localhost";
    private static final int PORT = 6380;
    private static final Instant NOW = Instant.parse("2026-09-26T03:00:00Z");

    private static LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private final DispatchEventPublisher publisher = mock(DispatchEventPublisher.class);
    private String outbox;
    private String inflight;
    private DispatchOutboxRelay relay;

    @BeforeAll
    static void requireRedis() {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(HOST, PORT), 300);
        } catch (IOException e) {
            assumeTrue(false, "레디스(" + HOST + ":" + PORT + ")가 없어서 건너뛴다");
        }
    }

    @BeforeEach
    void setUp() {
        if (factory == null) {
            factory = new LettuceConnectionFactory(HOST, PORT);
            factory.afterPropertiesSet();
        }
        redis = new StringRedisTemplate(factory);
        long id = Ids.newId();
        outbox = "test:outbox:" + id;
        inflight = "test:outbox:inflight:" + id;
        given(publisher.sendAsync(any())).willReturn(CompletableFuture.completedFuture(null));
        relay = new DispatchOutboxRelay(redis, publisher, outbox, inflight, Duration.ofSeconds(30),
                Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
    }

    @AfterEach
    void cleanUp() {
        redis.delete(List.of(outbox, inflight));
    }

    private static String envelope(String topic, long enqueuedAt) {
        return new OutboxEnvelope(topic, "1", "{\"n\":\"" + topic + "\"}", enqueuedAt).toJson();
    }

    @Test
    void sendsInOrderAndEmptiesBothLists() {
        redis.opsForList().rightPushAll(outbox, envelope("dispatch.assigned", NOW.toEpochMilli()),
                envelope("order.status", NOW.toEpochMilli()));

        assertThat(relay.drain()).isEqualTo(2);

        ArgumentCaptor<OutboxEnvelope> sent = ArgumentCaptor.forClass(OutboxEnvelope.class);
        verify(publisher, times(2)).sendAsync(sent.capture());
        assertThat(sent.getAllValues()).extracting(OutboxEnvelope::topic)
                .containsExactly("dispatch.assigned", "order.status");
        assertThat(redis.opsForList().size(outbox)).isZero();
        assertThat(redis.opsForList().size(inflight)).isZero();
    }

    /** 카프카가 못 받으면 지우지 않고 inflight 에 남긴다. 30초 뒤 recover 가 다시 보낸다 */
    @Test
    void keepsFailedItemsInflightWhenKafkaFails() {
        given(publisher.sendAsync(any())).willReturn(CompletableFuture.failedFuture(new IllegalStateException("브로커가 없다")));
        redis.opsForList().rightPushAll(outbox, envelope("a", NOW.toEpochMilli()), envelope("b", NOW.toEpochMilli()));

        assertThat(relay.drain()).isZero();

        assertThat(redis.opsForList().range(inflight, 0, -1)).hasSize(2);
        assertThat(redis.opsForList().size(outbox)).isZero();
    }

    /** 가운데 하나만 실패하면 그것만 남는다. 받았다고 한 앞뒤 것은 지운다. 다시 보낼 필요가 없다 */
    @Test
    void removesEverythingThatWasAcknowledgedEvenAfterAFailure() {
        given(publisher.sendAsync(any())).willAnswer(call -> {
            OutboxEnvelope e = call.getArgument(0);
            return "b".equals(e.topic())
                    ? CompletableFuture.failedFuture(new IllegalStateException("이것만 실패"))
                    : CompletableFuture.completedFuture(null);
        });
        redis.opsForList().rightPushAll(outbox, envelope("a", NOW.toEpochMilli()), envelope("b", NOW.toEpochMilli()),
                envelope("c", NOW.toEpochMilli()));

        relay.drain();

        assertThat(redis.opsForList().range(inflight, 0, -1)).hasSize(1).allMatch(item -> item.contains("\"b\""));
    }

    /** 한 묶음(100건)보다 많이 밀려 있어도 한 판에 이어서 보낸다 */
    @Test
    void drainsMoreThanOneBatchPerRun() {
        for (int i = 0; i < 250; i++) {
            redis.opsForList().rightPush(outbox, envelope("t" + i, NOW.toEpochMilli()));
        }

        assertThat(relay.drain()).isEqualTo(250);
        assertThat(redis.opsForList().size(outbox)).isZero();
        assertThat(redis.opsForList().size(inflight)).isZero();
    }

    /** 보내다 죽은 인스턴스가 남긴 걸 다시 보낸다. 방금 옮겨진 건 지금 누가 보내는 중일 수 있어서 건드리지 않는다 */
    @Test
    void recoverResendsOnlyItemsStuckLongerThanTheLimit() {
        String stuck = envelope("stuck", NOW.minusSeconds(31).toEpochMilli());
        String fresh = envelope("fresh", NOW.minusSeconds(5).toEpochMilli());
        redis.opsForList().rightPushAll(inflight, stuck, fresh);

        assertThat(relay.recover()).isEqualTo(1);

        ArgumentCaptor<OutboxEnvelope> sent = ArgumentCaptor.forClass(OutboxEnvelope.class);
        verify(publisher).sendAsync(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("stuck");
        assertThat(redis.opsForList().range(inflight, 0, -1)).containsExactly(fresh);
    }

    /** 못 읽는 항목은 버린다. 남겨두면 recover 가 영원히 붙잡고 있어서 뒤엣것까지 막는다 */
    @Test
    void dropsItemsItCannotRead() {
        redis.opsForList().rightPushAll(outbox, "이건 JSON 이 아니다", envelope("ok", NOW.toEpochMilli()));

        assertThat(relay.drain()).isEqualTo(2);

        verify(publisher, times(1)).sendAsync(any());
        assertThat(redis.opsForList().size(inflight)).isZero();
    }

    @Test
    void doesNothingWhenEmpty() {
        assertThat(relay.drain()).isZero();
        assertThat(relay.recover()).isZero();
        verify(publisher, never()).sendAsync(any());
    }
}
