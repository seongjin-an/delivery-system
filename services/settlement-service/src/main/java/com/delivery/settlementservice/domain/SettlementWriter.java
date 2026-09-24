package com.delivery.settlementservice.domain;

import com.delivery.common.Times;
import com.delivery.common.event.DeliveryCompleted;
import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * SE-01 배달 한 건을 정산에 넣는다. 기능 정의서 SE-01 규칙 1~4번 그대로다.
 *
 * <p>INSERT IGNORE 가 0 을 돌려주면 이미 집계한 주문이라 daily 를 안 건드린다. 두 문장이 한 트랜잭션이라,
 * detail 은 들어갔는데 daily 에 더하기 전에 죽는 순간이 없다. 그런 순간이 있으면 리플레이 때 detail 이
 * "이미 있음" 으로 막아서 그 한 건은 daily 에 영영 안 더해진다.
 */
@Component
@RequiredArgsConstructor
public class SettlementWriter {

    /** 정산은 한국 날짜로 끊는다. 새벽 1시(KST) 배달은 UTC 로는 전날 16시다 */
    static final ZoneId SETTLE_ZONE = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;

    /** @return 처음 본 주문이라 합계에 더했으면 true, 이미 집계한 주문이면 false */
    @Transactional
    public boolean record(DeliveryCompleted event) {
        validate(event);
        LocalDate settleDate = event.completedAt().atZone(SETTLE_ZONE).toLocalDate();
        int fee = FeePolicy.feeKrw(event.distanceMeters());

        // IGNORE 는 PK 중복만 삼키는 게 아니다. NOT NULL 에 null 이 오거나 값이 넘쳐도 에러 대신 경고로 바꾸고
        // 기본값을 넣는다. 그래서 넣기 전에 validate 로 값을 먼저 본다. 안 보면 zoneId 가 빠진 이벤트가
        // 빈 문자열로 조용히 들어간다.
        int inserted = jdbc.update("""
                INSERT IGNORE INTO settlement_detail
                    (order_id, rider_id, settle_date, zone_id, price_krw, distance_meters,
                     fee_krw, fee_policy, completed_at, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.orderId(), event.riderId(), settleDate, event.zoneId(), event.priceKrw(),
                event.distanceMeters(), fee, FeePolicy.VERSION,
                Timestamp.from(event.completedAt()), Timestamp.from(Times.now()));
        if (inserted == 0) {
            return false;
        }

        // VALUES() 함수는 MySQL 8.0.20 부터 쓰지 말라고 나온다. 새 행에 이름을 붙이는 문법(AS incoming)으로 쓴다.
        jdbc.update("""
                INSERT INTO settlement_daily
                    (rider_id, settle_date, order_count, distance_sum, fee_sum, updated_at)
                VALUES (?, ?, 1, ?, ?, ?) AS incoming
                ON DUPLICATE KEY UPDATE
                    order_count  = settlement_daily.order_count + 1,
                    distance_sum = settlement_daily.distance_sum + incoming.distance_sum,
                    fee_sum      = settlement_daily.fee_sum + incoming.fee_sum,
                    updated_at   = incoming.updated_at
                """,
                event.riderId(), settleDate, event.distanceMeters(), fee, Timestamp.from(Times.now()));
        return true;
    }

    /** 몇 번을 다시 해도 똑같이 틀린 이벤트다. BusinessException 이라 재시도 없이 DLT 로 간다 */
    private static void validate(DeliveryCompleted e) {
        if (e.orderId() <= 0 || e.riderId() <= 0 || e.completedAt() == null
                || e.zoneId() == null || e.zoneId().isBlank() || e.distanceMeters() < 0 || e.priceKrw() < 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "정산할 수 없는 배달 완료 이벤트다: " + e);
        }
    }
}
