package com.delivery.locationingest;

import com.delivery.common.event.RiderLocation;
import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import com.delivery.locationingest.config.IngestProperties;
import com.delivery.locationingest.ingest.LocationIngestService;
import com.delivery.locationingest.ingest.MoveFilter;
import com.delivery.locationingest.kafka.LocationPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class LocationIngestServiceTest {

    private static final long RIDER = 881520076849260058L;
    private static final double LAT = 37.498095;
    private static final double LNG = 127.027610;

    private final MoveFilterTest.MutableClock clock = new MoveFilterTest.MutableClock();
    private final LocationPublisher publisher = mock(LocationPublisher.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LocationIngestService service = new LocationIngestService(
            new MoveFilter(new IngestProperties(15, Duration.ofSeconds(10), 1000, Duration.ofMinutes(30)), clock),
            publisher, clock, registry);

    @Test
    void publishesWithServerComputedZone() {
        Instant sentAt = clock.instant().minusMillis(300);

        service.ingest(RIDER, LAT, LNG, sentAt);

        RiderLocation sent = captureOne();
        assertThat(sent.riderId()).isEqualTo(RIDER);
        assertThat(sent.zoneId()).isEqualTo("Z3749_12702");
        assertThat(sent.sentAt()).isEqualTo(sentAt);
    }

    @Test
    void outOfRangeIsRejectedAndNotPublished() {
        assertThatThrownBy(() -> service.ingest(RIDER, 0.0, 0.0, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_COORDINATE);

        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void rejectedPointDoesNotBecomeFilterBaseline() {
        // 범위 밖 좌표가 캐시에 들어갔다면 다음 정상 좌표는 MOVED 로 나갔을 거다. FIRST 여야 맞다.
        assertThatThrownBy(() -> service.ingest(RIDER, 0.0, 0.0, null));
        service.ingest(RIDER, LAT, LNG, null);

        assertThat(registry.counter("location_ingest_received_total", "verdict", "first").count()).isEqualTo(1);
        assertThat(registry.counter("location_ingest_received_total", "verdict", "moved").count()).isZero();
    }

    @Test
    void notMovedPointIsCountedButNotPublished() {
        service.ingest(RIDER, LAT, LNG, null);
        clock.advance(Duration.ofSeconds(3));
        service.ingest(RIDER, LAT, LNG, null);

        verify(publisher, times(1)).publish(any(), any());
        assertThat(registry.counter("location_ingest_received_total", "verdict", "skip").count()).isEqualTo(1);
    }

    @Test
    void futureSentAtIsReplacedWithServerTime() {
        service.ingest(RIDER, LAT, LNG, clock.instant().plus(Duration.ofHours(1)));

        assertThat(captureOne().sentAt()).isEqualTo(clock.instant());
        assertThat(registry.counter("location_ingest_future_sent_at_total").count()).isEqualTo(1);
    }

    @Test
    void missingSentAtIsFilledWithoutWarning() {
        service.ingest(RIDER, LAT, LNG, null);

        assertThat(captureOne().sentAt()).isEqualTo(clock.instant());
        assertThat(registry.counter("location_ingest_future_sent_at_total").count()).isZero();
    }

    @Test
    void publishFailureMakesNextPointGoOut() {
        service.ingest(RIDER, LAT, LNG, null);
        ArgumentCaptor<Runnable> onFailure = ArgumentCaptor.forClass(Runnable.class);
        verify(publisher).publish(any(), onFailure.capture());

        onFailure.getValue().run();
        clock.advance(Duration.ofSeconds(3));
        service.ingest(RIDER, LAT, LNG, null);

        verify(publisher, times(2)).publish(any(), any());
    }

    private RiderLocation captureOne() {
        ArgumentCaptor<RiderLocation> captor = ArgumentCaptor.forClass(RiderLocation.class);
        verify(publisher).publish(captor.capture(), any());
        return captor.getValue();
    }
}
