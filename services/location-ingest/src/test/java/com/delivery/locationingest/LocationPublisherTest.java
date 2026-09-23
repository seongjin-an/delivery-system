package com.delivery.locationingest;

import com.delivery.common.JsonUtil;
import com.delivery.common.KafkaTopics;
import com.delivery.common.event.RiderLocation;
import com.delivery.locationingest.kafka.LocationPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LocationPublisherTest {

    private static final RiderLocation LOCATION = new RiderLocation(
            881520076849260058L, 37.498095, 127.027610, "Z3749_12702", Instant.parse("2026-09-23T04:00:00Z"));

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LocationPublisher publisher = new LocationPublisher(kafkaTemplate, registry);
    private final AtomicInteger failures = new AtomicInteger();

    @Test
    void sendsWithRiderIdAsKey() {
        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willReturn(new CompletableFuture<SendResult<String, String>>());

        publisher.publish(LOCATION, failures::incrementAndGet);

        verify(kafkaTemplate).send(KafkaTopics.RIDER_LOCATION, "881520076849260058", JsonUtil.toJson(LOCATION));
    }

    @Test
    void asyncFailureIsCountedAndReported() {
        given(kafkaTemplate.send(eq(KafkaTopics.RIDER_LOCATION), anyString(), anyString()))
                .willReturn(CompletableFuture.failedFuture(new TimeoutException("브로커 응답 없음")));

        publisher.publish(LOCATION, failures::incrementAndGet);

        assertThat(failures).hasValue(1);
        assertThat(registry.counter("location_ingest_publish_failed_total").count()).isEqualTo(1);
    }

    @Test
    void syncFailureIsSwallowed() {
        // 메타데이터를 못 받으면 send() 가 max.block.ms 뒤 그 자리에서 던진다
        given(kafkaTemplate.send(eq(KafkaTopics.RIDER_LOCATION), anyString(), anyString()))
                .willThrow(new TimeoutException("Topic rider.location not present in metadata after 500 ms"));

        assertThatCode(() -> publisher.publish(LOCATION, failures::incrementAndGet)).doesNotThrowAnyException();
        assertThat(failures).hasValue(1);
        assertThat(registry.counter("location_ingest_publish_failed_total").count()).isEqualTo(1);
    }
}
