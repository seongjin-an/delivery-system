package com.delivery.orderapi.domain;

import com.delivery.common.Ids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * OR-07 의 조건부 갱신을 진짜 MySQL 에 돌려본다.
 *
 * <p>규칙 3번(PICKED_UP 이 ASSIGNED 로 되돌아가면 안 된다)은 WHERE 절 하나에 달려 있다.
 * 목으로는 "내가 짠 목이 1을 돌려준다" 만 확인하게 되고, JPQL 의 IN 절이 enum 을 어떻게 묶는지는
 * DB 가 답해줘야 안다.
 *
 * <p>개발용 MySQL 을 같이 쓴다. @DataJpaTest 가 테스트마다 롤백해서 행이 남지 않는다.
 * MySQL 이 없으면 통째로 건너뛴다. {@code ./scripts/start.sh} 로 인프라를 띄우면 돈다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:33306/delivery?serverTimezone=UTC&characterEncoding=UTF-8",
        "spring.datasource.username=dev_user",
        "spring.datasource.password=dev_password",
        "spring.jpa.hibernate.ddl-auto=update"
})
class OrderRepositoryMysqlTest {

    private static final Set<OrderStatus> BEFORE_RESULT = EnumSet.of(OrderStatus.CREATED, OrderStatus.DISPATCHING);
    private static final long RIDER_ID = 881520076849260058L;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeAll
    static void requireMysql() {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("localhost", 33306), 300);
        } catch (IOException e) {
            assumeTrue(false, "MySQL(localhost:33306)이 없어서 건너뛴다. ./scripts/start.sh 로 띄우면 돈다");
        }
    }

    @Test
    void assignsFromDispatchingAndFillsRiderAndAttempt() {
        long orderId = givenOrder(OrderStatus.DISPATCHING);

        int changed = orderRepository.assign(orderId, BEFORE_RESULT, RIDER_ID, 3, Instant.now());

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(changed).isEqualTo(1);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.ASSIGNED);
        assertThat(order.getRiderId()).isEqualTo(RIDER_ID);
        assertThat(order.getAttempt()).isEqualTo(3);
    }

    @Test
    void assignsEvenBeforeDispatchingArrives() {
        // DISPATCHING 과 ASSIGNED 는 토픽이 달라서 ASSIGNED 가 먼저 올 수 있다
        long orderId = givenOrder(OrderStatus.CREATED);

        assertThat(orderRepository.assign(orderId, BEFORE_RESULT, RIDER_ID, 1, Instant.now())).isEqualTo(1);
    }

    @Test
    void duplicateAssignedChangesNothing() {
        long orderId = givenOrder(OrderStatus.DISPATCHING);
        orderRepository.assign(orderId, BEFORE_RESULT, RIDER_ID, 1, Instant.now());

        assertThat(orderRepository.assign(orderId, BEFORE_RESULT, RIDER_ID + 1, 2, Instant.now())).isZero();
        assertThat(orderRepository.findById(orderId).orElseThrow().getRiderId()).isEqualTo(RIDER_ID);
    }

    @Test
    void lateAssignedDoesNotRollBackPickedUp() {
        long orderId = givenOrder(OrderStatus.PICKED_UP);

        assertThat(orderRepository.assign(orderId, BEFORE_RESULT, RIDER_ID, 1, Instant.now())).isZero();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PICKED_UP);
    }

    @Test
    void lateDispatchingDoesNotRollBackAssigned() {
        long orderId = givenOrder(OrderStatus.ASSIGNED);

        int changed = orderRepository.transition(
                orderId, EnumSet.of(OrderStatus.CREATED), OrderStatus.DISPATCHING, Instant.now());

        assertThat(changed).isZero();
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.ASSIGNED);
    }

    @Test
    void cancelledOrderIsNotRevivedByLateResult() {
        long orderId = givenOrder(OrderStatus.CANCELLED);

        assertThat(orderRepository.assign(orderId, BEFORE_RESULT, RIDER_ID, 1, Instant.now())).isZero();
        assertThat(orderRepository.fail(orderId, BEFORE_RESULT, 5, Instant.now())).isZero();
    }

    @Test
    void failKeepsHowManyOffersWentOut() {
        long orderId = givenOrder(OrderStatus.DISPATCHING);

        assertThat(orderRepository.fail(orderId, BEFORE_RESULT, 5, Instant.now())).isEqualTo(1);

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getAttempt()).isEqualTo(5);
    }

    // ── OR-03, OR-04 의 advance ───────────────────────────────────────────

    @Test
    void assignedRiderPicksUp() {
        long orderId = givenAssignedTo(RIDER_ID);

        assertThat(orderRepository.advance(orderId, RIDER_ID, OrderStatus.ASSIGNED, OrderStatus.PICKED_UP, Instant.now()))
                .isEqualTo(1);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.PICKED_UP);
    }

    @Test
    void otherRiderCannotPickUp() {
        long orderId = givenAssignedTo(RIDER_ID);

        assertThat(orderRepository.advance(orderId, RIDER_ID + 1, OrderStatus.ASSIGNED, OrderStatus.PICKED_UP, Instant.now()))
                .isZero();
    }

    @Test
    void cannotCompleteWithoutPickUp() {
        long orderId = givenAssignedTo(RIDER_ID);

        assertThat(orderRepository.advance(orderId, RIDER_ID, OrderStatus.PICKED_UP, OrderStatus.DELIVERED, Instant.now()))
                .isZero();
    }

    private long givenAssignedTo(long riderId) {
        long orderId = givenOrder(OrderStatus.DISPATCHING);
        orderRepository.assign(orderId, BEFORE_RESULT, riderId, 1, Instant.now());
        return orderId;
    }

    /** 원하는 상태의 주문을 하나 만든다. 상태는 transition 으로 밀어 넣는다 */
    private long givenOrder(OrderStatus status) {
        long orderId = Ids.newId();
        orderRepository.saveAndFlush(Order.create(orderId, "store-or07",
                37.498095, 127.027610, 37.504198, 127.048985,
                "Z3749_12702", 18000, 2004, Instant.now()));
        if (status != OrderStatus.CREATED) {
            orderRepository.transition(orderId, EnumSet.allOf(OrderStatus.class), status, Instant.now());
        }
        return orderId;
    }
}
