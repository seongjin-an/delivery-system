package com.delivery.common.dispatch;

import com.delivery.common.JsonUtil;
import com.delivery.common.KafkaTopics;
import com.delivery.common.Times;
import com.delivery.common.event.DispatchAssigned;
import com.delivery.common.event.OrderStatusChanged;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 2단계 실험: 배차 확정 이벤트를 카프카로 바로 보내지 않고 order-api 의 outbox 테이블에 넣는다.
 *
 * <p>현황판(order_dispatch)이 MySQL 에 있으니, 수락 UPDATE 와 이 INSERT 를 <b>한 트랜잭션</b>에 묶을 수 있다.
 * 둘 다 들어가거나 둘 다 안 들어간다. 내보내는 건 Debezium 이 이미 하고 있다(destination_topic 으로 라우팅).
 *
 * <p>레디스 판에서는 이게 안 된다. 수락 Lua 가 ACCEPTED 를 쓴 뒤 카프카로 보내기 전에 dispatch-engine 이 죽으면
 * 이벤트가 끝내 안 나가서 주문이 DISPATCHING 으로 영영 남는다. 실제로 kill -9 한 판에서 2건이 그렇게 남았다.
 *
 * <p>대신 dispatch-engine 이 order-api 의 테이블에 직접 쓴다. 서비스 둘이 DB 를 나눠 쓰게 되는 대가가 있다.
 */
public class MysqlDispatchOutbox {

    private static final String SQL = """
            INSERT INTO outbox (aggregate_id, attempt_count, created_at, destination_topic, partition_key, payload)
            VALUES (?, 0, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public MysqlDispatchOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** DispatchEventPublisher#publishAssigned 와 같은 두 이벤트를 같은 모양으로 넣는다 */
    public void recordAssigned(long orderId, long riderId, long offerId, int attempt) {
        Instant now = Times.now();
        insert(orderId, KafkaTopics.DISPATCH_ASSIGNED, new DispatchAssigned(orderId, riderId, offerId, attempt, now), now);
        insert(orderId, KafkaTopics.ORDER_STATUS, new OrderStatusChanged(orderId, riderId, "ASSIGNED", now), now);
    }

    private void insert(long orderId, String topic, Object payload, Instant now) {
        jdbc.update(SQL, orderId, Timestamp.from(now), topic, Long.toString(orderId), JsonUtil.toJson(payload));
    }
}
