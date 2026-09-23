package com.delivery.orderapi.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * 지금 상태가 {@code from} 중 하나일 때만 {@code to} 로 바꾼다. 바뀐 행 수를 돌려준다.
     *
     * <p>엔티티를 읽어서 setter 로 바꾸고 저장하는 방식을 안 쓴 이유는, 읽는 순간과 쓰는 순간 사이에
     * 다른 이벤트가 끼어들 수 있어서다. dispatch.assigned 와 order.status 가 서로 다른 토픽이라 두 스레드가
     * 같은 주문을 동시에 만질 수 있다. 조건을 WHERE 에 넣으면 MySQL 이 행 잠금을 잡은 채로 판정해서
     * 둘 중 하나만 바꾼다.
     *
     * <p>clearAutomatically 를 켰다. 같은 트랜잭션 안에서 이 주문을 이미 읽어뒀다면 영속성 컨텍스트에
     * 옛 상태가 남아 있어서, 뒤이어 읽으면 방금 바꾼 값이 안 보인다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Order o
               SET o.status = :to, o.updatedAt = :now
             WHERE o.orderId = :orderId
               AND o.status IN :from
            """)
    int transition(@Param("orderId") long orderId,
                   @Param("from") Collection<OrderStatus> from,
                   @Param("to") OrderStatus to,
                   @Param("now") Instant now);

    /**
     * 배정된 라이더가 한 칸 진행시킨다 (OR-03 픽업, OR-04 완료).
     *
     * <p>기능 정의서 OR-03 규칙 그대로다. rider_id 까지 WHERE 에 넣어서, 다른 라이더가 남의 주문을
     * 픽업 처리하는 걸 같은 문장 안에서 막는다. 영향 행 수가 0이면 왜 0인지는 부르는 쪽이 따로 본다.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Order o
               SET o.status = :to, o.updatedAt = :now
             WHERE o.orderId = :orderId
               AND o.riderId = :riderId
               AND o.status = :from
            """)
    int advance(@Param("orderId") long orderId,
                @Param("riderId") long riderId,
                @Param("from") OrderStatus from,
                @Param("to") OrderStatus to,
                @Param("now") Instant now);

    /** ASSIGNED 로 갈 때는 라이더와 attempt 도 같이 채운다. 조건은 transition 과 같다 */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Order o
               SET o.status = com.delivery.orderapi.domain.OrderStatus.ASSIGNED,
                   o.riderId = :riderId, o.attempt = :attempt, o.updatedAt = :now
             WHERE o.orderId = :orderId
               AND o.status IN :from
            """)
    int assign(@Param("orderId") long orderId,
               @Param("from") Collection<OrderStatus> from,
               @Param("riderId") long riderId,
               @Param("attempt") int attempt,
               @Param("now") Instant now);
}
