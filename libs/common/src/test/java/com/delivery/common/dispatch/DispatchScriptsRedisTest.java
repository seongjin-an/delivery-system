package com.delivery.common.dispatch;

import com.delivery.common.Ids;
import com.delivery.common.RedisKeys;
import com.delivery.common.autoconfigure.CommonDispatchAutoConfiguration;
import com.delivery.common.rider.RiderStateFields;
import com.delivery.common.rider.RiderStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Lua 스크립트를 진짜 레디스에 돌려본다.
 *
 * <p>여기 있는 다섯 개가 배차에서 <b>동시성을 책임지는 코드 전부</b>다. 목으로 검증해봐야
 * "내가 짠 목이 내 기대대로 동작한다" 만 확인하는 꼴이라, 반환값이 정말 -1 인지 -2 인지는
 * 레디스가 직접 돌려줘야 안다. 실제로 이 스크립트들의 분기 하나가 틀리면 증상이 "가끔 라이더
 * 두 명이 같은 가게에 간다" 로 나타나는데, 그건 재현이 거의 안 된다.
 *
 * <p>레디스가 없으면 통째로 건너뛴다. {@code ./scripts/start.sh} 로 인프라를 띄우면 돈다.
 */
class DispatchScriptsRedisTest {

    private static final String HOST = "localhost";
    private static final int PORT = 6380;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RedisAutoConfiguration.class, CommonDispatchAutoConfiguration.class))
            .withPropertyValues(
                    "spring.data.redis.host=" + HOST,
                    "spring.data.redis.port=" + PORT);

    private long orderId;
    private long riderId;
    private long offerId;

    @BeforeAll
    static void requireRedis() {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(HOST, PORT), 300);
        } catch (IOException e) {
            assumeTrue(false, "레디스(" + HOST + ":" + PORT + ")가 없어서 건너뛴다. "
                    + "./scripts/start.sh 로 띄우면 돈다");
        }
    }

    @BeforeEach
    void freshIds() {
        // 개발용 레디스를 같이 쓰기 때문에 매번 새 아이디로 논다. 고정 아이디를 쓰면
        // 실제로 돌려본 주문의 상태를 테스트가 덮어쓴다.
        orderId = Ids.newId();
        riderId = Ids.newId();
        offerId = Ids.newId();
    }

    @AfterEach
    void cleanUp() {
        run((context, redis) -> redis.delete(List.of(
                RedisKeys.offer(orderId), RedisKeys.offerIndex(offerId),
                RedisKeys.candidates(orderId), RedisKeys.riderState(riderId),
                RedisKeys.riderLock(riderId), RedisKeys.dispatchLock(orderId))));
    }

    private interface Body {
        void accept(AssertableApplicationContext context, StringRedisTemplate redis);
    }

    private void run(Body body) {
        contextRunner.run(context ->
                body.accept(context, context.getBean(StringRedisTemplate.class)));
    }

    private void withBoard(Consumer<AssertableApplicationContext> body) {
        run((context, redis) -> {
            context.getBean(OfferBoard.class).writeOffered(orderId, offerId, riderId, 1, 1000L);
            body.accept(context);
        });
    }

    private static String state(StringRedisTemplate redis, long orderId) {
        return (String) redis.opsForHash().get(RedisKeys.offer(orderId), OfferFields.STATE);
    }

    // ── respond-offer.lua (DE-04 수락 / DE-05 거절) ────────────────────────

    @Test
    void acceptsOfferOnce() {
        withBoard(context -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);

            assertThat(board.respond(orderId, offerId, riderId, OfferState.ACCEPTED, 5000L))
                    .isEqualTo(OfferDecision.APPLIED);
            assertThat(state(redis, orderId)).isEqualTo("ACCEPTED");
            assertThat(redis.opsForHash().get(RedisKeys.offer(orderId), OfferFields.RESPONDED_AT))
                    .isEqualTo("5000");
        });
    }

    /** 같은 사람이 버튼을 두 번 눌렀거나 앱이 재전송했다 → 409 */
    @Test
    void rejectsSecondAccept() {
        withBoard(context -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            board.respond(orderId, offerId, riderId, OfferState.ACCEPTED, 5000L);

            assertThat(board.respond(orderId, offerId, riderId, OfferState.ACCEPTED, 6000L))
                    .isEqualTo(OfferDecision.ALREADY_TAKEN);
        });
    }

    /**
     * 1순위가 만료돼 2순위로 넘어간 뒤, 1순위가 뒤늦게 수락한 경우.
     *
     * <p>이 한 건을 위해 Lua 에서 offerId 검사를 riderId 검사보다 먼저 둔다. 순서를 뒤집으면
     * 보드의 riderId 가 이미 2순위라서 403 "당신 제안이 아니에요" 가 나가는데, 라이더 입장에선
     * 분명히 자기한테 왔던 제안이라 틀린 말이다. 맞는 말은 410 "만료됐어요" 다.
     */
    @Test
    void tellsLateAcceptorItExpiredNotThatItWasNotTheirs() {
        withBoard(context -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            long nextOfferId = Ids.newId();
            long nextRiderId = Ids.newId();
            board.writeOffered(orderId, nextOfferId, nextRiderId, 2, 20000L);

            assertThat(board.respond(orderId, offerId, riderId, OfferState.ACCEPTED, 21000L))
                    .isEqualTo(OfferDecision.EXPIRED);
        });
    }

    @Test
    void rejectsAcceptFromAnotherRider() {
        withBoard(context -> {
            OfferBoard board = context.getBean(OfferBoard.class);

            assertThat(board.respond(orderId, offerId, Ids.newId(), OfferState.ACCEPTED, 5000L))
                    .isEqualTo(OfferDecision.NOT_YOURS);
        });
    }

    @Test
    void treatsMissingBoardAsExpired() {
        run((context, redis) ->
                assertThat(context.getBean(OfferBoard.class)
                        .respond(orderId, offerId, riderId, OfferState.ACCEPTED, 5000L))
                        .isEqualTo(OfferDecision.EXPIRED));
    }

    @Test
    void marksRejectedWhenRiderDeclines() {
        withBoard(context -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);

            assertThat(board.respond(orderId, offerId, riderId, OfferState.REJECTED, 3000L))
                    .isEqualTo(OfferDecision.APPLIED);
            assertThat(state(redis, orderId)).isEqualTo("REJECTED");
        });
    }

    /** 이미 수락한 제안은 거절할 수 없다 */
    @Test
    void rejectsDeclineAfterAccept() {
        withBoard(context -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            board.respond(orderId, offerId, riderId, OfferState.ACCEPTED, 5000L);

            assertThat(board.respond(orderId, offerId, riderId, OfferState.REJECTED, 6000L))
                    .isEqualTo(OfferDecision.ALREADY_TAKEN);
        });
    }

    // ── expire-offer.lua (RE-02) ──────────────────────────────────────────

    @Test
    void expiresOfferedAndTellsRelayToRetry() {
        withBoard(context -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);

            assertThat(board.expire(orderId, offerId, 11000L)).isEqualTo(ExpiryDecision.RETRY);
            assertThat(state(redis, orderId)).isEqualTo("EXPIRED");
            assertThat(redis.opsForHash().get(RedisKeys.offer(orderId), OfferFields.EXPIRED_AT))
                    .isEqualTo("11000");
        });
    }

    /** 펜싱 규칙. 라이더가 3초에 거절해 2순위로 넘어간 뒤 10초에 도착한 1순위 타이머 */
    @Test
    void dropsExpiryThatLostFencing() {
        withBoard(context -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            board.writeOffered(orderId, Ids.newId(), Ids.newId(), 2, 4000L);

            assertThat(board.expire(orderId, offerId, 11000L)).isEqualTo(ExpiryDecision.STALE);
        });
    }

    /** 9.9초에 수락, 10.0초에 만료. 수락이 이겼으니 재제안하면 안 된다 */
    @Test
    void refusesToExpireAnAcceptedOffer() {
        withBoard(context -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            board.respond(orderId, offerId, riderId, OfferState.ACCEPTED, 9900L);

            assertThat(board.expire(orderId, offerId, 10000L)).isEqualTo(ExpiryDecision.ACCEPTED);
        });
    }

    /**
     * DE-05 가 거절을 받고 곧바로 DLX 에 넣은 메시지. 이건 정상 경로라 재제안해야 한다.
     * 상태는 REJECTED 로 남겨둔다 — "안 받았다" 와 "거절했다" 는 지표에서 다른 이야기다.
     */
    @Test
    void retriesRejectedOfferAndKeepsTheRejectedState() {
        withBoard(context -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
            board.respond(orderId, offerId, riderId, OfferState.REJECTED, 3000L);

            assertThat(board.expire(orderId, offerId, 3100L)).isEqualTo(ExpiryDecision.RETRY);
            assertThat(state(redis, orderId)).isEqualTo("REJECTED");
        });
    }

    /** 만료까지만 찍고 재제안 전에 죽어서 메시지가 다시 온 경우. 이어서 하면 된다 */
    @Test
    void retriesAgainWhenExpiryMessageIsRedelivered() {
        withBoard(context -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            board.expire(orderId, offerId, 11000L);

            assertThat(board.expire(orderId, offerId, 11500L)).isEqualTo(ExpiryDecision.RETRY);
        });
    }

    @Test
    void refusesToExpireAClosedOrder() {
        withBoard(context -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            board.writeState(orderId, OfferState.FAILED);

            assertThat(board.expire(orderId, offerId, 11000L)).isEqualTo(ExpiryDecision.CLOSED);
        });
    }

    @Test
    void reportsGoneWhenBoardIsMissing() {
        run((context, redis) -> assertThat(
                context.getBean(OfferBoard.class).expire(orderId, offerId, 11000L))
                .isEqualTo(ExpiryDecision.GONE));
    }

    // ── release-rider.lua (DE-05 / RE-02) ─────────────────────────────────

    @Test
    void releasesRiderHoldingMyOffer() {
        run((context, redis) -> {
            RiderState riderState = context.getBean(RiderState.class);
            context.getBean(RiderLock.class).claim(riderId, orderId);
            riderState.markOffered(riderId, offerId);

            assertThat(riderState.release(riderId, orderId, offerId)).isTrue();

            Map<Object, Object> dump = riderState.dump(riderId);
            assertThat(dump.get(RiderStateFields.STATUS)).isEqualTo(RiderStatus.IDLE.name());
            assertThat(dump.get(RiderStateFields.IDLE_SINCE)).isNotNull();
            assertThat(redis.hasKey(RedisKeys.riderLock(riderId))).isFalse();
        });
    }

    /**
     * 거절 직후 다른 주문이 이 라이더를 채간 경우. 조건 없이 IDLE 로 쓰면 2번 제안을 들고 있는
     * 라이더가 한가한 걸로 보여서, 3번 주문이 또 뽑아간다.
     */
    @Test
    void leavesRiderAloneWhenTheyAlreadyHoldAnotherOffer() {
        run((context, redis) -> {
            RiderState riderState = context.getBean(RiderState.class);
            long otherOrderId = Ids.newId();
            context.getBean(RiderLock.class).claim(riderId, otherOrderId);
            riderState.markOffered(riderId, Ids.newId());

            assertThat(riderState.release(riderId, orderId, offerId)).isFalse();

            assertThat(riderState.dump(riderId).get(RiderStateFields.STATUS))
                    .isEqualTo(RiderStatus.OFFERED.name());
            // 남의 찜을 지우면 그 주문이 라이더를 두 번 잡힌다
            assertThat(redis.opsForValue().get(RedisKeys.riderLock(riderId)))
                    .isEqualTo(Long.toString(otherOrderId));
        });
    }

    /** 배달 중인 라이더는 제안 만료가 건드릴 대상이 아니다 */
    @Test
    void leavesDeliveringRiderAlone() {
        run((context, redis) -> {
            RiderState riderState = context.getBean(RiderState.class);
            riderState.markDelivering(riderId, orderId);

            assertThat(riderState.release(riderId, orderId, offerId)).isFalse();
            assertThat(riderState.dump(riderId).get(RiderStateFields.STATUS))
                    .isEqualTo(RiderStatus.DELIVERING.name());
        });
    }

    // ── finish-delivery.lua (OR-04) ───────────────────────────────────────

    @Test
    void finishDeliveryFreesRiderDeliveringThisOrder() {
        run((context, redis) -> {
            RiderState riderState = context.getBean(RiderState.class);
            riderState.markDelivering(riderId, orderId);

            assertThat(riderState.finishDelivery(riderId, orderId)).isTrue();

            Map<Object, Object> dump = riderState.dump(riderId);
            assertThat(dump.get(RiderStateFields.STATUS)).isEqualTo(RiderStatus.IDLE.name());
            // 대기 보너스가 0부터 다시 세져야 한다. 빼먹어도 에러가 안 나서 여기서 본다
            assertThat(dump.get(RiderStateFields.IDLE_SINCE)).isNotNull();
            assertThat(dump.get(RiderStateFields.CURRENT_ORDER_ID)).isEqualTo("");
        });
    }

    /**
     * 완료 요청이 재시도로 늦게 한 번 더 온 경우. 첫 요청이 이미 풀어줬고, 그 사이 다른 주문의 제안을 받았다.
     * 조건 없이 IDLE 로 쓰면 제안을 들고 있는 라이더가 한가한 사람이 돼서 다른 주문이 또 뽑아간다.
     */
    @Test
    void lateRetriedFinishLeavesNewOfferAlone() {
        run((context, redis) -> {
            RiderState riderState = context.getBean(RiderState.class);
            riderState.markDelivering(riderId, orderId);
            riderState.finishDelivery(riderId, orderId);
            riderState.markOffered(riderId, offerId);

            assertThat(riderState.finishDelivery(riderId, orderId)).isFalse();

            assertThat(riderState.dump(riderId).get(RiderStateFields.STATUS)).isEqualTo(RiderStatus.OFFERED.name());
        });
    }

    @Test
    void finishDeliveryIgnoresRiderDeliveringAnotherOrder() {
        run((context, redis) -> {
            RiderState riderState = context.getBean(RiderState.class);
            long otherOrderId = Ids.newId();
            riderState.markDelivering(riderId, otherOrderId);

            assertThat(riderState.finishDelivery(riderId, orderId)).isFalse();

            assertThat(riderState.dump(riderId).get(RiderStateFields.CURRENT_ORDER_ID))
                    .isEqualTo(Long.toString(otherOrderId));
        });
    }

    // ── cancel-offer.lua (OR-05) ──────────────────────────────────────────

    @Test
    void cancelOfLiveOfferTellsWhoWasHoldingIt() {
        run((context, redis) -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            board.writeOffered(orderId, offerId, riderId, 2, 1000L);

            OfferBoard.Cancellation cancelled = board.cancel(orderId, 3000L);

            assertThat(cancelled.previous()).isEqualTo(OfferState.OFFERED);
            assertThat(cancelled.riderId()).isEqualTo(riderId);
            assertThat(cancelled.offerId()).isEqualTo(offerId);
            assertThat(board.read(orderId).state()).isEqualTo(OfferState.CANCELLED);
        });
    }

    /** 취소한 뒤 라이더가 수락을 누르면 410 이어야 한다. 수락이 먼저 이기면 취소된 주문에 배차된다 */
    @Test
    void acceptAfterCancelIsRefused() {
        run((context, redis) -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            board.writeOffered(orderId, offerId, riderId, 1, 1000L);
            board.cancel(orderId, 3000L);

            assertThat(board.respond(orderId, offerId, riderId, OfferState.ACCEPTED, 4000L).isApplied()).isFalse();
            assertThat(board.read(orderId).state()).isEqualTo(OfferState.CANCELLED);
        });
    }

    /** 10초 타이머가 뒤늦게 와도 재제안하지 않는다 (기능 정의서 OR-05 규칙 2번) */
    @Test
    void expiryAfterCancelDoesNotReoffer() {
        run((context, redis) -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            board.writeOffered(orderId, offerId, riderId, 1, 1000L);
            board.cancel(orderId, 3000L);

            assertThat(board.expire(orderId, offerId, 11000L)).isEqualTo(ExpiryDecision.CLOSED);
        });
    }

    /**
     * 아직 제안이 한 번도 안 나간 주문을 취소한 경우. 보드를 안 만들면 뒤늦게 order.created 를 읽은 dispatch-engine 이
     * "처음 보는 주문" 으로 보고 배차를 시작한다. TTL 도 붙어야 한다 — 안 붙으면 취소한 주문 수만큼 키가 영원히 쌓인다.
     */
    @Test
    void cancelBeforeAnyOfferLeavesCancelledBoardWithTtl() {
        run((context, redis) -> {
            OfferBoard board = context.getBean(OfferBoard.class);

            OfferBoard.Cancellation cancelled = board.cancel(orderId, 3000L);

            assertThat(cancelled.previous()).isNull();
            assertThat(board.read(orderId).state()).isEqualTo(OfferState.CANCELLED);
            assertThat(redis.getExpire(RedisKeys.offer(orderId))).isPositive();
        });
    }

    // ── save-candidates.lua (DE-02) ───────────────────────────────────────

    @Test
    void replacesCandidatesAndAlwaysLeavesATtl() {
        run((context, redis) -> {
            CandidateList candidates = context.getBean(CandidateList.class);
            candidates.replace(orderId, List.of(1L, 2L, 3L));
            candidates.replace(orderId, List.of(7L, 8L));

            assertThat(candidates.remaining(orderId)).containsExactly("7", "8");
            // TTL 이 안 붙으면 배차가 끝난 뒤에도 키가 영원히 남는다
            assertThat(redis.getExpire(RedisKeys.candidates(orderId))).isPositive();
        });
    }

    @Test
    void popsCandidatesInOrder() {
        run((context, redis) -> {
            CandidateList candidates = context.getBean(CandidateList.class);
            candidates.replace(orderId, List.of(11L, 22L));

            assertThat(candidates.next(orderId)).isEqualTo(11L);
            assertThat(candidates.next(orderId)).isEqualTo(22L);
            assertThat(candidates.next(orderId)).isNull();
        });
    }

    // ── release-lock.lua ──────────────────────────────────────────────────

    /**
     * A 가 느려져서 TTL 을 넘기고 B 가 락을 새로 잡은 뒤, 뒤늦게 끝난 A 가 지우려 드는 장면.
     * 값을 안 보고 DEL 하면 B 의 락이 지워져서 C 가 같이 들어온다.
     */
    @Test
    void refusesToReleaseSomeoneElsesLease() {
        run((context, redis) -> {
            DispatchLease lease = context.getBean(DispatchLease.class);
            lease.acquire(orderId, "dispatch-engine:8093:100");

            assertThat(lease.release(orderId, "dispatch-engine:8193:200")).isFalse();
            assertThat(lease.release(orderId, "dispatch-engine:8093:100")).isTrue();
            assertThat(redis.hasKey(RedisKeys.dispatchLock(orderId))).isFalse();
        });
    }
}
