package com.delivery.geoindexer;

import com.delivery.common.JsonUtil;
import com.delivery.common.event.RiderLocation;
import com.delivery.geoindexer.index.IndexResult;
import com.delivery.geoindexer.index.RiderIndexer;
import com.delivery.geoindexer.kafka.RiderLocationListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiderLocationListenerTest {

    private static final long RIDER_ID = 881520076849260058L;

    @Mock private RiderIndexer riderIndexer;
    @Mock private Acknowledgment ack;

    private MeterRegistry meterRegistry;
    private RiderLocationListener listener;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        listener = new RiderLocationListener(riderIndexer, meterRegistry, Duration.ofSeconds(10));
        given(riderIndexer.index(anyList())).willReturn(new IndexResult(1, 1, 0));
    }

    private static String json(long riderId, double lat, double lng) {
        return JsonUtil.toJson(
                new RiderLocation(riderId, lat, lng, "Z3749_12702", Instant.now()));
    }

    /** 방금 발행된 레코드들 */
    private static List<ConsumerRecord<String, String>> records(String... payloads) {
        return Arrays.stream(payloads)
                .map(p -> recordAt(System.currentTimeMillis(), p))
                .toList();
    }

    private static ConsumerRecord<String, String> recordAt(long timestamp, String payload) {
        return new ConsumerRecord<>("rider.location", 0, 0L, timestamp, TimestampType.CREATE_TIME,
                -1, -1, null, payload, new RecordHeaders(), Optional.empty());
    }

    private double dropped(String reason) {
        return meterRegistry.get("geo_index_dropped_total").tag("reason", reason).counter().count();
    }

    @SuppressWarnings("unchecked")
    private List<RiderLocation> indexedLocations() {
        ArgumentCaptor<List<RiderLocation>> captor = ArgumentCaptor.forClass(List.class);
        verify(riderIndexer).index(captor.capture());
        return captor.getValue();
    }

    @Test
    void passesValidLocationsToTheIndexer() {
        listener.onLocations(records(json(RIDER_ID, 37.498095, 127.027610)), ack);

        assertThat(indexedLocations()).singleElement()
                .extracting(RiderLocation::riderId).isEqualTo(RIDER_ID);
        verify(ack).acknowledge();
    }

    /**
     * 못 읽는 레코드 하나가 배치 전체를 못 쓰게 만들면 안 된다.
     * 실험하다 손으로 넣어본 값이나 예전 포맷이 토픽에 남아 있을 수 있다.
     */
    @Test
    void skipsMalformedRecordAndKeepsTheRest() {
        listener.onLocations(records(
                "이건 JSON 이 아니다",
                json(RIDER_ID, 37.498095, 127.027610)), ack);

        assertThat(indexedLocations()).hasSize(1);
        assertThat(dropped("malformed")).isEqualTo(1);
    }

    /**
     * 범위 밖 좌표가 GEOADD 로 들어가면 레디스가 거절하면서 파이프라인이 통째로 실패한다.
     * 좌표 하나 때문에 멀쩡한 라이더 전부가 같이 날아간다.
     */
    @Test
    void skipsOutOfRangeCoordinateBeforeItReachesRedis() {
        listener.onLocations(records(
                json(RIDER_ID, 35.6895, 139.6917),          // 도쿄
                json(RIDER_ID + 1, 37.498095, 127.027610)), ack);

        assertThat(indexedLocations()).singleElement()
                .extracting(RiderLocation::riderId).isEqualTo(RIDER_ID + 1);
        assertThat(dropped("out_of_range")).isEqualTo(1);
    }

    /**
     * 레디스가 죽어도 재시도하지 않고 ack 한다. 재시도하면 파티션이 막혀서 밀린 좌표가
     * 더 쌓이는데, 신선도가 전부인 데이터에서 밀리는 건 잃는 것보다 나쁘다.
     */
    @Test
    void acknowledgesEvenWhenIndexingFails() {
        willThrow(new IllegalStateException("레디스가 죽었다"))
                .given(riderIndexer).index(anyList());

        listener.onLocations(records(json(RIDER_ID, 37.498095, 127.027610)), ack);

        verify(ack).acknowledge();
        assertThat(dropped("redis_error")).isEqualTo(1);
    }

    /** 전부 버려도 인덱서를 부르긴 한다(빈 리스트). 그래도 ack 는 나가야 한다 */
    @Test
    void acknowledgesWhenEverythingWasDropped() {
        listener.onLocations(records("망가진 레코드", "이것도"), ack);

        assertThat(indexedLocations()).isEmpty();
        verify(ack).acknowledge();
    }

    /** 받은 레코드 수와 쓴 라이더 수를 따로 센다. 둘의 차이가 배치 안에서 걷어낸 중복이다 */
    @Test
    void countsReceivedRecordsSeparatelyFromIndexedRiders() {
        given(riderIndexer.index(anyList())).willReturn(new IndexResult(3, 1, 0));

        listener.onLocations(records(
                json(RIDER_ID, 37.49, 127.02),
                json(RIDER_ID, 37.50, 127.03),
                json(RIDER_ID, 37.51, 127.04)), ack);

        assertThat(meterRegistry.get("geo_index_records_total").counter().count()).isEqualTo(3);
        assertThat(meterRegistry.get("geo_index_riders_total").counter().count()).isEqualTo(1);
    }

    @Test
    void neverRetriesByThrowing() {
        willThrow(new IllegalStateException("레디스가 죽었다"))
                .given(riderIndexer).index(anyList());

        listener.onLocations(records(json(RIDER_ID, 37.498095, 127.027610)), ack);

        // 예외가 새어 나가면 스프링이 재시도하고 DLT 까지 보낸다. 위치에는 그게 손해다.
        verify(ack).acknowledge();
        verify(ack, never()).nack(any(Duration.class));
    }

    /**
     * 5분 끄고 다시 켜면 커밋한 자리부터 다시 읽는다. 그때 묵은 좌표를 레디스에 쓰면
     * 라이더가 5분 전 자리로 돌아간다. 2단계 실험에서 실제로 310초 묵은 걸 썼다.
     */
    @Test
    void dropsRecordsOlderThanMaxAge() {
        long now = System.currentTimeMillis();

        listener.onLocations(List.of(
                recordAt(now - 300_000, json(RIDER_ID, 37.49, 127.02)),       // 5분 전
                recordAt(now - 11_000, json(RIDER_ID, 37.50, 127.03)),        // 11초 전
                recordAt(now - 1_000, json(RIDER_ID + 1, 37.51, 127.04))), ack);

        assertThat(indexedLocations()).singleElement()
                .extracting(RiderLocation::riderId).isEqualTo(RIDER_ID + 1);
        assertThat(dropped("stale")).isEqualTo(2);
        // 버린 것도 받은 건 받은 거다. 랙과 맞춰보려면 여기엔 들어가야 한다
        assertThat(meterRegistry.get("geo_index_records_total").counter().count()).isEqualTo(3);
        verify(ack).acknowledge();
    }

    /**
     * 휴대폰 시계가 늦어도 버리면 안 된다. sentAt 이 아니라 레코드 타임스탬프를 보는 이유다.
     * sentAt 을 봤다면 시계가 30초 느린 라이더는 좌표가 전부 버려져서 지도에서 사라진다.
     */
    @Test
    void judgesAgeByRecordTimestampNotByPhoneClock() {
        String slowPhone = JsonUtil.toJson(new RiderLocation(
                RIDER_ID, 37.49, 127.02, "Z3749_12702", Instant.now().minusSeconds(30)));

        listener.onLocations(List.of(recordAt(System.currentTimeMillis(), slowPhone)), ack);

        assertThat(indexedLocations()).hasSize(1);
        assertThat(dropped("stale")).isZero();
    }

    /** 타임스탬프가 없는 레코드(-1)는 나이를 모르니 살린다 */
    @Test
    void keepsRecordsWithoutTimestamp() {
        listener.onLocations(List.of(recordAt(-1L, json(RIDER_ID, 37.49, 127.02))), ack);

        assertThat(indexedLocations()).hasSize(1);
    }

    /** 0 이면 끈다. 시나리오 B 에서 랙이 쌓이는 걸 그대로 봐야 할 때 쓴다 */
    @Test
    void keepsEverythingWhenMaxAgeIsZero() {
        listener = new RiderLocationListener(riderIndexer, meterRegistry, Duration.ZERO);

        listener.onLocations(List.of(
                recordAt(System.currentTimeMillis() - 300_000, json(RIDER_ID, 37.49, 127.02))), ack);

        assertThat(indexedLocations()).hasSize(1);
    }
}
