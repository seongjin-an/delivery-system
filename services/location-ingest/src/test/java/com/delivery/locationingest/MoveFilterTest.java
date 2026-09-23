package com.delivery.locationingest;

import com.delivery.locationingest.config.IngestProperties;
import com.delivery.locationingest.ingest.MoveFilter;
import com.delivery.locationingest.ingest.MoveFilter.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MoveFilterTest {

    private static final long RIDER = 881520076849260058L;

    /** 강남역 근처 */
    private static final double LAT = 37.498095;
    private static final double LNG = 127.027610;

    /** 위도 0.0001도가 약 11.1m. 10m, 20m 를 만들 때 쓴다 */
    private static final double LAT_PER_METER = 0.0001 / 11.1195;

    private final MutableClock clock = new MutableClock();
    private final MoveFilter filter = new MoveFilter(
            new IngestProperties(15, Duration.ofSeconds(10), 1000, Duration.ofMinutes(30)), clock);

    @Test
    void firstPointPublishes() {
        assertThat(filter.check(RIDER, LAT, LNG)).isEqualTo(Verdict.FIRST);
    }

    @Test
    void skipsWhenMovedLessThan15m() {
        filter.check(RIDER, LAT, LNG);
        clock.advance(Duration.ofSeconds(3));

        assertThat(filter.check(RIDER, LAT + 10 * LAT_PER_METER, LNG)).isEqualTo(Verdict.SKIP);
    }

    @Test
    void publishesWhenMoved15mOrMore() {
        filter.check(RIDER, LAT, LNG);
        clock.advance(Duration.ofSeconds(3));

        assertThat(filter.check(RIDER, LAT + 20 * LAT_PER_METER, LNG)).isEqualTo(Verdict.MOVED);
    }

    @Test
    void slowRiderIsComparedWithLastPublishedPointNotLastReceived() {
        // 3초에 10m 씩 간다. 받은 좌표끼리 비교하면 매번 10m 라 영영 안 나간다.
        filter.check(RIDER, LAT, LNG);
        clock.advance(Duration.ofSeconds(3));
        assertThat(filter.check(RIDER, LAT + 10 * LAT_PER_METER, LNG)).isEqualTo(Verdict.SKIP);
        clock.advance(Duration.ofSeconds(3));

        // 발행한 점에서 20m 가 됐으니 나간다
        assertThat(filter.check(RIDER, LAT + 20 * LAT_PER_METER, LNG)).isEqualTo(Verdict.MOVED);
    }

    @Test
    void standingRiderIsSentOnceEvery10Seconds() {
        filter.check(RIDER, LAT, LNG);

        clock.advance(Duration.ofSeconds(3));
        assertThat(filter.check(RIDER, LAT, LNG)).isEqualTo(Verdict.SKIP);
        clock.advance(Duration.ofSeconds(3));
        assertThat(filter.check(RIDER, LAT, LNG)).isEqualTo(Verdict.SKIP);
        clock.advance(Duration.ofSeconds(3));
        assertThat(filter.check(RIDER, LAT, LNG)).isEqualTo(Verdict.SKIP);
        clock.advance(Duration.ofSeconds(3));   // 12초째
        assertThat(filter.check(RIDER, LAT, LNG)).isEqualTo(Verdict.KEEPALIVE);

        // KEEPALIVE 도 발행이라 시계가 다시 0부터 간다
        clock.advance(Duration.ofSeconds(3));
        assertThat(filter.check(RIDER, LAT, LNG)).isEqualTo(Verdict.SKIP);
    }

    @Test
    void forgetMakesNextPointPublishEvenIfNotMoved() {
        filter.check(RIDER, LAT, LNG);
        filter.forget(RIDER, LAT, LNG);
        clock.advance(Duration.ofSeconds(3));

        assertThat(filter.check(RIDER, LAT, LNG)).isEqualTo(Verdict.FIRST);
    }

    @Test
    void lateForgetDoesNotEraseNewerPoint() {
        // 첫 발행의 실패 콜백이 늦게 와서, 그 사이 다음 좌표가 이미 캐시에 들어간 경우
        filter.check(RIDER, LAT, LNG);
        clock.advance(Duration.ofSeconds(3));
        double newLat = LAT + 20 * LAT_PER_METER;
        filter.check(RIDER, newLat, LNG);

        filter.forget(RIDER, LAT, LNG);
        clock.advance(Duration.ofSeconds(3));

        assertThat(filter.check(RIDER, newLat, LNG)).isEqualTo(Verdict.SKIP);
    }

    @Test
    void ridersAreFilteredIndependently() {
        filter.check(RIDER, LAT, LNG);

        assertThat(filter.check(RIDER + 1, LAT, LNG)).isEqualTo(Verdict.FIRST);
    }

    /** 10초짜리 판정을 진짜 10초 기다리지 않으려고 손으로 돌리는 시계 */
    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-23T04:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
