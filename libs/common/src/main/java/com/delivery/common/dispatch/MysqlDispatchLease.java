package com.delivery.common.dispatch;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 2단계 실험: 배차 리스({@code SET NX PX 15000})를 {@code order_dispatch.lease_until} 컬럼으로 한다.
 *
 * <p>잡기: 행이 없으면 만들고, "리스가 비었거나 시간이 지났으면 내 걸로" 를 UPDATE 한 줄로 한다.
 * 1행이 바뀌면 잡은 거다. 풀기: "내 거면 비워라" 도 한 줄이다. 레디스에선 풀기를 Lua 로 해야 했다
 * (GET 후 DEL 사이에 TTL 이 지나 남의 락을 지우는 문제). SQL 은 WHERE 가 그걸 공짜로 해준다.
 *
 * <p>차이가 하나 있다. 시간을 앱이 넘긴다. 레디스 TTL 은 레디스 시계로 흐르지만, 여기선 인스턴스마다
 * 자기 시계로 lease_until 을 비교한다. 서버 시계가 몇 초 어긋나면 남의 리스를 일찍 빼앗는다.
 */
public class MysqlDispatchLease extends DispatchLease {

    private final JdbcTemplate jdbc;
    private final long leaseMillis;

    public MysqlDispatchLease(JdbcTemplate jdbc, OfferProperties properties) {
        super(null, null, properties);
        this.jdbc = jdbc;
        this.leaseMillis = properties.leaseTtl().toMillis();
    }

    @Override
    public String acquire(long orderId, String owner) {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT IGNORE INTO order_dispatch (order_id) VALUES (?)", orderId);
        int changed = jdbc.update("""
                UPDATE order_dispatch SET lease_owner = ?, lease_until = ?
                 WHERE order_id = ? AND (lease_until IS NULL OR lease_until < ?)
                """, owner, now + leaseMillis, orderId, now);
        return changed == 1 ? owner : null;
    }

    @Override
    public boolean release(long orderId, String owner) {
        return jdbc.update("""
                UPDATE order_dispatch SET lease_owner = NULL, lease_until = NULL
                 WHERE order_id = ? AND lease_owner = ?
                """, orderId, owner) == 1;
    }
}
