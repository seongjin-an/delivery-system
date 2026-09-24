package com.delivery.settlementservice.domain;

import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** SE-02 정산 조회. settlement_daily 만 읽는다 */
@Component
@RequiredArgsConstructor
public class SettlementQuery {

    /** 한 번에 석 달까지. 라이더 한 명 1년치를 한 번에 긁어도 365행이라 무겁진 않지만, 그 이상은 화면이 아니라 배치가 할 일이다 */
    static final long MAX_DAYS = 92;

    private final JdbcTemplate jdbc;

    public record Day(LocalDate date, int orderCount, long distanceSum, long feeSum) {
    }

    public record Summary(long riderId, LocalDate from, LocalDate to, List<Day> days,
                          int orderCount, long distanceSum, long feeSum) {
    }

    public Summary find(long riderId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "from 이 to 보다 늦어요");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "한 번에 " + MAX_DAYS + "일까지만 볼 수 있어요");
        }
        List<Day> days = jdbc.query("""
                SELECT settle_date, order_count, distance_sum, fee_sum
                  FROM settlement_daily
                 WHERE rider_id = ? AND settle_date BETWEEN ? AND ?
                 ORDER BY settle_date
                """,
                (rs, i) -> new Day(rs.getObject("settle_date", LocalDate.class), rs.getInt("order_count"),
                        rs.getLong("distance_sum"), rs.getLong("fee_sum")),
                riderId, from, to);
        return new Summary(riderId, from, to, days,
                days.stream().mapToInt(Day::orderCount).sum(),
                days.stream().mapToLong(Day::distanceSum).sum(),
                days.stream().mapToLong(Day::feeSum).sum());
    }
}
