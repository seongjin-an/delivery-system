package com.delivery.common.dispatch;

import com.delivery.common.Ids;
import com.delivery.common.autoconfigure.CommonDispatchAutoConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 2단계 실험: MySQL 판 현황판과 리스가 Lua 판과 똑같이 판정하는지 본다.
 *
 * <p>DispatchScriptsRedisTest 의 현황판, 리스 경우들을 그대로 옮겼다. 두 저장소를 비교하려면 같은 입력에 같은
 * 답이 나와야 한다. 여기에 수락과 만료를 동시에 날리는 경주를 더했다. UPDATE ... WHERE 가 정말 한쪽만
 * 이기게 해주는지는 한 번 돌려서는 모른다.
 */
class MysqlDispatchStateTest {

    private static final String HOST = "localhost";
    private static final int PORT = 33306;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class,
                    RedisAutoConfiguration.class, CommonDispatchAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:mysql://" + HOST + ":" + PORT + "/delivery?serverTimezone=UTC",
                    "spring.datasource.username=dev_user",
                    "spring.datasource.password=dev_password",
                    "delivery.dispatch-state.store=mysql",
                    "delivery.offer.lease-ttl=1s");

    private long orderId;
    private long riderId;
    private long offerId;

    @BeforeAll
    static void requireMysql() {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(HOST, PORT), 300);
        } catch (IOException e) {
            assumeTrue(false, "MySQL(" + HOST + ":" + PORT + ")이 없어서 건너뛴다");
        }
    }

    @BeforeEach
    void freshIds() {
        orderId = Ids.newId();
        riderId = Ids.newId();
        offerId = Ids.newId();
    }

    private void run(Consumer<AssertableApplicationContext> body) {
        contextRunner.run(context -> {
            context.getBean(JdbcTemplate.class).execute("""
                    CREATE TABLE IF NOT EXISTS order_dispatch (
                        order_id BIGINT NOT NULL PRIMARY KEY, offer_id BIGINT NULL, rider_id BIGINT NULL,
                        state VARCHAR(16) NULL, attempt INT NOT NULL DEFAULT 0, offered_at BIGINT NULL,
                        responded_at BIGINT NULL, expired_at BIGINT NULL, cancelled_at BIGINT NULL,
                        lease_owner VARCHAR(100) NULL, lease_until BIGINT NULL,
                        KEY idx_order_dispatch_offer (offer_id)) ENGINE = InnoDB""");
            body.accept(context);
        });
    }

    private void withBoard(Consumer<OfferBoard> body) {
        run(context -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            assertThat(board).isInstanceOf(MysqlOfferBoard.class);
            board.writeOffered(orderId, offerId, riderId, 1, 1000L);
            body.accept(board);
        });
    }

    // ── DE-04 수락 / DE-05 거절 ─────────────────────────────────────────────

    @Test
    void acceptsOfferOnce() {
        withBoard(board -> {
            assertThat(board.respond(orderId, offerId, riderId, OfferState.ACCEPTED, 5000L)).isEqualTo(OfferDecision.APPLIED);
            assertThat(board.read(orderId).state()).isEqualTo(OfferState.ACCEPTED);
            assertThat(board.dump(orderId)).containsEntry("responded_at", "5000");
        });
    }

    @Test
    void rejectsSecondAccept() {
        withBoard(board -> {
            board.respond(orderId, offerId, riderId, OfferState.ACCEPTED, 5000L);
            assertThat(board.respond(orderId, offerId, riderId, OfferState.ACCEPTED, 6000L)).isEqualTo(OfferDecision.ALREADY_TAKEN);
        });
    }

    @Test
    void tellsLateAcceptorItExpiredNotThatItWasNotTheirs() {
        withBoard(board -> {
            board.writeOffered(orderId, Ids.newId(), Ids.newId(), 2, 20000L);
            assertThat(board.respond(orderId, offerId, riderId, OfferState.ACCEPTED, 21000L)).isEqualTo(OfferDecision.EXPIRED);
        });
    }

    @Test
    void rejectsAcceptFromAnotherRider() {
        withBoard(board ->
                assertThat(board.respond(orderId, offerId, Ids.newId(), OfferState.ACCEPTED, 5000L)).isEqualTo(OfferDecision.NOT_YOURS));
    }

    @Test
    void treatsMissingBoardAsExpired() {
        run(context -> assertThat(context.getBean(OfferBoard.class)
                .respond(orderId, offerId, riderId, OfferState.ACCEPTED, 5000L)).isEqualTo(OfferDecision.EXPIRED));
    }

    @Test
    void marksRejectedWhenRiderDeclines() {
        withBoard(board -> {
            assertThat(board.respond(orderId, offerId, riderId, OfferState.REJECTED, 3000L)).isEqualTo(OfferDecision.APPLIED);
            assertThat(board.read(orderId).state()).isEqualTo(OfferState.REJECTED);
        });
    }

    @Test
    void rejectsDeclineAfterAccept() {
        withBoard(board -> {
            board.respond(orderId, offerId, riderId, OfferState.ACCEPTED, 5000L);
            assertThat(board.respond(orderId, offerId, riderId, OfferState.REJECTED, 6000L)).isEqualTo(OfferDecision.ALREADY_TAKEN);
        });
    }

    @Test
    void findsOrderByOfferId() {
        withBoard(board -> assertThat(board.findOrderId(offerId)).isEqualTo(orderId));
    }

    // ── RE-02 만료 ──────────────────────────────────────────────────────────

    @Test
    void expiresOfferedAndTellsRelayToRetry() {
        withBoard(board -> {
            assertThat(board.expire(orderId, offerId, 11000L)).isEqualTo(ExpiryDecision.RETRY);
            assertThat(board.read(orderId).state()).isEqualTo(OfferState.EXPIRED);
            assertThat(board.dump(orderId)).containsEntry("expired_at", "11000");
        });
    }

    @Test
    void dropsExpiryThatLostFencing() {
        withBoard(board -> {
            board.writeOffered(orderId, Ids.newId(), Ids.newId(), 2, 4000L);
            assertThat(board.expire(orderId, offerId, 11000L)).isEqualTo(ExpiryDecision.STALE);
        });
    }

    @Test
    void refusesToExpireAnAcceptedOffer() {
        withBoard(board -> {
            board.respond(orderId, offerId, riderId, OfferState.ACCEPTED, 9900L);
            assertThat(board.expire(orderId, offerId, 10000L)).isEqualTo(ExpiryDecision.ACCEPTED);
        });
    }

    @Test
    void retriesRejectedOfferAndKeepsTheRejectedState() {
        withBoard(board -> {
            board.respond(orderId, offerId, riderId, OfferState.REJECTED, 3000L);
            assertThat(board.expire(orderId, offerId, 3100L)).isEqualTo(ExpiryDecision.RETRY);
            assertThat(board.read(orderId).state()).isEqualTo(OfferState.REJECTED);
        });
    }

    @Test
    void retriesAgainWhenExpiryMessageIsRedelivered() {
        withBoard(board -> {
            board.expire(orderId, offerId, 11000L);
            assertThat(board.expire(orderId, offerId, 11500L)).isEqualTo(ExpiryDecision.RETRY);
        });
    }

    @Test
    void refusesToExpireAClosedOrder() {
        withBoard(board -> {
            board.writeState(orderId, OfferState.FAILED);
            assertThat(board.expire(orderId, offerId, 11000L)).isEqualTo(ExpiryDecision.CLOSED);
        });
    }

    @Test
    void reportsGoneWhenBoardIsMissing() {
        run(context -> assertThat(context.getBean(OfferBoard.class).expire(orderId, offerId, 11000L)).isEqualTo(ExpiryDecision.GONE));
    }

    // ── OR-05 취소 ──────────────────────────────────────────────────────────

    @Test
    void cancelOfLiveOfferTellsWhoWasHoldingIt() {
        run(context -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            board.writeOffered(orderId, offerId, riderId, 2, 1000L);

            OfferBoard.Cancellation cancelled = board.cancel(orderId, 3000L);

            assertThat(cancelled.previous()).isEqualTo(OfferState.OFFERED);
            assertThat(cancelled.riderId()).isEqualTo(riderId);
            assertThat(cancelled.offerId()).isEqualTo(offerId);
            assertThat(board.read(orderId).state()).isEqualTo(OfferState.CANCELLED);
        });
    }

    @Test
    void acceptAfterCancelIsRefused() {
        withBoard(board -> {
            board.cancel(orderId, 3000L);
            assertThat(board.respond(orderId, offerId, riderId, OfferState.ACCEPTED, 4000L).isApplied()).isFalse();
            assertThat(board.read(orderId).state()).isEqualTo(OfferState.CANCELLED);
        });
    }

    @Test
    void expiryAfterCancelDoesNotReoffer() {
        withBoard(board -> {
            board.cancel(orderId, 3000L);
            assertThat(board.expire(orderId, offerId, 11000L)).isEqualTo(ExpiryDecision.CLOSED);
        });
    }

    @Test
    void cancelBeforeAnyOfferLeavesCancelledBoard() {
        run(context -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            assertThat(board.cancel(orderId, 3000L).previous()).isNull();
            assertThat(board.read(orderId).state()).isEqualTo(OfferState.CANCELLED);
        });
    }

    // ── 배차 리스 ───────────────────────────────────────────────────────────

    @Test
    void refusesToReleaseSomeoneElsesLease() {
        run(context -> {
            DispatchLease lease = context.getBean(DispatchLease.class);
            assertThat(lease).isInstanceOf(MysqlDispatchLease.class);
            assertThat(lease.acquire(orderId, "dispatch-engine:8093:100")).isNotNull();

            assertThat(lease.acquire(orderId, "offer-relay:8094:150")).isNull();
            assertThat(lease.release(orderId, "dispatch-engine:8193:200")).isFalse();
            assertThat(lease.release(orderId, "dispatch-engine:8093:100")).isTrue();
            assertThat(lease.acquire(orderId, "offer-relay:8094:300")).isNotNull();
        });
    }

    /** 잡은 쪽이 죽어서 안 풀어도 시간이 지나면 남이 잡는다. 레디스 TTL 이 해주던 일이다 */
    @Test
    void leaseCanBeTakenAfterItRunsOut() {
        run(context -> {
            DispatchLease lease = context.getBean(DispatchLease.class);
            lease.acquire(orderId, "dispatch-engine:8093:100");
            try {
                Thread.sleep(1_100);   // lease-ttl 을 1초로 줄여뒀다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            assertThat(lease.acquire(orderId, "offer-relay:8094:200")).isNotNull();
            // 먼저 잡았던 쪽이 뒤늦게 풀려고 하면 거절된다. 남의 리스를 지우면 안 된다
            assertThat(lease.release(orderId, "dispatch-engine:8093:100")).isFalse();
        });
    }

    /**
     * 수락과 만료를 동시에 100번 날린다. 매번 정확히 한쪽만 이겨야 한다.
     * 둘 다 이기면 라이더 화면엔 "배차 완료" 가 뜨는데 주문은 2순위에게 넘어간다(respond-offer.lua 주석의 그 장면).
     */
    @Test
    void acceptAndExpireNeverBothWin() throws Exception {
        run(context -> {
            OfferBoard board = context.getBean(OfferBoard.class);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                for (int i = 0; i < 100; i++) {
                    long order = Ids.newId(), offer = Ids.newId(), rider = Ids.newId();
                    board.writeOffered(order, offer, rider, 1, 1000L);
                    CountDownLatch go = new CountDownLatch(1);
                    Future<OfferDecision> accept = pool.submit(() -> {
                        go.await();
                        return board.respond(order, offer, rider, OfferState.ACCEPTED, 9999L);
                    });
                    Future<ExpiryDecision> expire = pool.submit(() -> {
                        go.await();
                        return board.expire(order, offer, 10000L);
                    });
                    go.countDown();
                    boolean accepted = accept.get() == OfferDecision.APPLIED;
                    boolean expired = expire.get() == ExpiryDecision.RETRY;
                    assertThat(accepted ^ expired).as("round %d: accept=%s expire=%s", i, accept.get(), expire.get()).isTrue();
                    assertThat(board.read(order).state()).isEqualTo(accepted ? OfferState.ACCEPTED : OfferState.EXPIRED);
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                pool.shutdownNow();
            }
        });
    }
}
