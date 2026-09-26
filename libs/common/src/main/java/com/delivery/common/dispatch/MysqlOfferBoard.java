package com.delivery.common.dispatch;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2단계 실험: 제안 현황판을 레디스 해시 대신 MySQL {@code order_dispatch} 행 하나로 둔다.
 *
 * <p>Lua 세 개(respond, expire, cancel)가 하던 "읽고 비교하고 쓰기" 를 {@code UPDATE ... WHERE} 한 줄로 한다.
 * 조건이 맞으면 1행이 바뀌고, 누가 먼저 바꿨으면 0행이다. 0행일 때만 왜 안 됐는지 한 번 더 읽어서 이유를
 * 가른다. 판정은 UPDATE 에서 끝났으니 이유를 읽는 사이에 상태가 바뀌어도 결과가 뒤집히진 않는다.
 *
 * <p>레디스와 달리 TTL 이 없다. 행은 계속 남는다. 대신 offerId 인덱스를 따로 둘 필요가 없다(컬럼 인덱스 하나).
 * 레디스에선 보드와 인덱스 키가 따로라 TTL 을 맞춰야 했다.
 */
public class MysqlOfferBoard extends OfferBoard {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public MysqlOfferBoard(JdbcTemplate jdbc, TransactionTemplate tx, OfferProperties properties) {
        super(null, null, null, null, properties);
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** OR-05. 이전 상태를 돌려줘야 해서 한 줄 UPDATE 로는 안 된다. 행을 잠그고 읽은 뒤 바꾼다 */
    @Override
    public Cancellation cancel(long orderId, long cancelledAt) {
        return tx.execute(status -> {
            // 없으면 CANCELLED 만 든 행을 만든다. 뒤늦게 온 order.created 가 배차를 시작하지 않게 (cancel-offer.lua 와 같다)
            jdbc.update("INSERT IGNORE INTO order_dispatch (order_id) VALUES (?)", orderId);
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT state, rider_id, offer_id FROM order_dispatch WHERE order_id = ? FOR UPDATE", orderId);
            jdbc.update("UPDATE order_dispatch SET state = 'CANCELLED', cancelled_at = ? WHERE order_id = ?",
                    cancelledAt, orderId);
            return new Cancellation(OfferState.parseOrNull((String) row.get("state")),
                    number(row.get("rider_id")), number(row.get("offer_id")));
        });
    }

    @Override
    public OfferSnapshot read(long orderId) {
        List<OfferSnapshot> rows = jdbc.query(
                "SELECT offer_id, rider_id, state, attempt, offered_at FROM order_dispatch WHERE order_id = ?",
                (rs, i) -> {
                    OfferState state = OfferState.parseOrNull(rs.getString("state"));
                    return state == null ? null : new OfferSnapshot(rs.getLong("offer_id"), rs.getLong("rider_id"),
                            state, rs.getInt("attempt"), rs.getLong("offered_at"));
                }, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public Map<String, String> dump(long orderId) {
        Map<String, String> dump = new LinkedHashMap<>();
        try {
            jdbc.queryForMap("SELECT * FROM order_dispatch WHERE order_id = ?", orderId)
                    .forEach((k, v) -> dump.put(k, String.valueOf(v)));
        } catch (EmptyResultDataAccessException ignored) {
            // 없으면 빈 맵. 레디스 HGETALL 과 같다
        }
        return dump;
    }

    @Override
    public void writeOffered(long orderId, long offerId, long riderId, int attempt, long offeredAt) {
        jdbc.update("""
                INSERT INTO order_dispatch (order_id, offer_id, rider_id, state, attempt, offered_at)
                VALUES (?, ?, ?, 'OFFERED', ?, ?)
                ON DUPLICATE KEY UPDATE offer_id = VALUES(offer_id), rider_id = VALUES(rider_id), state = 'OFFERED',
                                        attempt = VALUES(attempt), offered_at = VALUES(offered_at)
                """, orderId, offerId, riderId, attempt, offeredAt);
    }

    @Override
    public Long findOrderId(long offerId) {
        List<Long> ids = jdbc.queryForList("SELECT order_id FROM order_dispatch WHERE offer_id = ?", Long.class, offerId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** DE-04 / DE-05. respond-offer.lua 와 같은 판정을 UPDATE 한 줄 + 이유 읽기로 한다 */
    @Override
    public OfferDecision respond(long orderId, long offerId, long riderId, OfferState target, long respondedAt) {
        int changed = jdbc.update("""
                UPDATE order_dispatch SET state = ?, responded_at = ?
                 WHERE order_id = ? AND offer_id = ? AND rider_id = ? AND state = 'OFFERED'
                """, target.name(), respondedAt, orderId, offerId, riderId);
        if (changed == 1) {
            return OfferDecision.APPLIED;
        }
        OfferSnapshot now = read(orderId);
        if (now == null || now.offerId() != offerId) {
            return OfferDecision.EXPIRED;          // offerId 를 riderId 보다 먼저 본다 (lua 주석 참고)
        }
        if (now.riderId() != riderId) {
            return OfferDecision.NOT_YOURS;
        }
        return now.state() == OfferState.ACCEPTED ? OfferDecision.ALREADY_TAKEN : OfferDecision.EXPIRED;
    }

    /** RE-02. expire-offer.lua 와 같은 판정 */
    @Override
    public ExpiryDecision expire(long orderId, long offerId, long expiredAt) {
        int changed = jdbc.update("""
                UPDATE order_dispatch SET state = 'EXPIRED', expired_at = ?
                 WHERE order_id = ? AND offer_id = ? AND state = 'OFFERED'
                """, expiredAt, orderId, offerId);
        if (changed == 1) {
            return ExpiryDecision.RETRY;
        }
        OfferSnapshot now = read(orderId);
        if (now == null) {
            return ExpiryDecision.GONE;
        }
        if (now.offerId() != offerId) {
            return ExpiryDecision.STALE;
        }
        return switch (now.state()) {
            case ACCEPTED -> ExpiryDecision.ACCEPTED;
            case CANCELLED, FAILED -> ExpiryDecision.CLOSED;
            default -> ExpiryDecision.RETRY;       // EXPIRED, REJECTED — lua 와 같다
        };
    }

    @Override
    public void writeState(long orderId, OfferState state) {
        jdbc.update("""
                INSERT INTO order_dispatch (order_id, state) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE state = VALUES(state)
                """, orderId, state.name());
    }

    @Override
    public void clear(long orderId, long offerId) {
        // 레디스 쪽은 보드와 인덱스를 지운다. 여기선 행을 지우면 리스 컬럼까지 같이 날아가서 제안 칸만 비운다
        jdbc.update("UPDATE order_dispatch SET offer_id = NULL, rider_id = NULL, state = NULL, offered_at = NULL "
                + "WHERE order_id = ? AND offer_id = ?", orderId, offerId);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
