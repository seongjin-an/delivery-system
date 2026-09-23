package com.delivery.notificationworker;

import com.delivery.common.event.PushMessage;
import com.delivery.notificationworker.config.PushProperties;
import com.delivery.notificationworker.push.PushDelivery;
import com.delivery.notificationworker.push.PushDelivery.Outcome;
import com.delivery.notificationworker.push.PushFailedException;
import com.delivery.notificationworker.push.PushSender;
import com.delivery.notificationworker.ratelimit.TokenBucket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class PushDeliveryTest {

    private static final Instant NOW = Instant.parse("2026-09-23T04:00:10Z");

    private final PushSender sender = mock(PushSender.class);
    private final TokenBucket tokenBucket = mock(TokenBucket.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PushProperties properties = new PushProperties(200, 0, 0, "", 3,
            Duration.ZERO, 3, Duration.ZERO);
    private final PushDelivery delivery = new PushDelivery(sender, tokenBucket, properties,
            Clock.fixed(NOW, ZoneOffset.UTC), registry);

    @BeforeEach
    void tokensAvailable() throws Exception {
        given(tokenBucket.acquireWithWait()).willReturn(true);
    }

    @Test
    void sendsOfferWithTimeActuallyLeft() throws Exception {
        // 4초 전에 나간 제안이면 라이더한테 남은 건 6초다. 10초라고 보내면 앱은 6초째에 410 을 받는다
        PushMessage offer = offerSentAgo(Duration.ofSeconds(4));

        assertThat(delivery.deliver(offer)).isEqualTo(Outcome.SENT);
        verify(sender).send(offer, 6);
    }

    @Test
    void offerThatExpiredInQueueIsNotSentAndSpendsNoToken() throws Exception {
        PushMessage offer = offerSentAgo(Duration.ofMillis(9_500));

        assertThat(delivery.deliver(offer)).isEqualTo(Outcome.EXPIRED);
        verify(tokenBucket, never()).acquireWithWait();
        verify(sender, never()).send(any(), anyLong());
        assertThat(registry.counter("push_expired_total").count()).isEqualTo(1);
    }

    @Test
    void retriesAndSucceedsOnThirdAttempt() throws Exception {
        PushMessage offer = offerSentAgo(Duration.ofSeconds(1));
        willThrow(new PushFailedException("1"), new PushFailedException("2")).willDoNothing()
                .given(sender).send(eq(offer), anyLong());

        assertThat(delivery.deliver(offer)).isEqualTo(Outcome.SENT);
        // 재시도도 외부 API 에는 한 번의 요청이다. 토큰을 매번 받는다
        verify(tokenBucket, times(3)).acquireWithWait();
        assertThat(registry.counter("push_failed_total", "kind", "offer").count()).isEqualTo(2);
    }

    @Test
    void goesToDlqAfterMaxAttempts() throws Exception {
        PushMessage offer = offerSentAgo(Duration.ofSeconds(1));
        willThrow(new PushFailedException("늘 실패")).given(sender).send(eq(offer), anyLong());

        assertThat(delivery.deliver(offer)).isEqualTo(Outcome.DEAD);
        verify(sender, times(3)).send(eq(offer), anyLong());
        assertThat(registry.counter("push_dead_total", "kind", "offer").count()).isEqualTo(1);
    }

    @Test
    void goesToDlqWhenNoTokenAfterWaiting() throws Exception {
        given(tokenBucket.acquireWithWait()).willReturn(false);

        assertThat(delivery.deliver(offerSentAgo(Duration.ofSeconds(1)))).isEqualTo(Outcome.RATE_LIMITED);
        assertThat(registry.counter("push_ratelimited_total", "kind", "offer").count()).isEqualTo(1);
    }

    @Test
    void marketingNeverExpires() throws Exception {
        willDoNothing().given(sender).send(any(), anyLong());

        assertThat(delivery.deliver(PushMessage.marketing(0, "점심 할인"))).isEqualTo(Outcome.SENT);
        assertThat(registry.counter("push_sent_total", "kind", "marketing").count()).isEqualTo(1);
    }

    private static PushMessage offerSentAgo(Duration ago) {
        return new PushMessage(PushMessage.Kind.OFFER, 881520076849260058L, 890391265973758795L,
                890391260219157193L, NOW.minus(ago), null);
    }
}
