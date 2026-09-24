package com.delivery.settlementservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 라이더별 하루 합계. 기능 정의서 SE-01 의 settlement_daily. 이것도 테이블을 만드는 용도로만 쓴다.
 *
 * <p>이 테이블만 두고 {@code fee_sum = fee_sum + ?} 로 더하면 안 된다. 오프셋을 리셋해서 과거분을 다시 흘리면
 * 합계가 정확히 두 배가 된다. settlement_detail 에 처음 들어간 주문일 때만 여기에 더한다.
 */
@Entity
@Table(name = "settlement_daily")
@IdClass(SettlementDaily.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementDaily {

    @Id
    @Column(name = "rider_id", nullable = false)
    private long riderId;

    @Id
    @Column(name = "settle_date", nullable = false)
    private LocalDate settleDate;

    @Column(name = "order_count", nullable = false)
    private int orderCount;

    /** 하루에 수백 km 는 안 가지만, int 로 두면 합계라서 언젠가 넘는다 */
    @Column(name = "distance_sum", nullable = false)
    private long distanceSum;

    @Column(name = "fee_sum", nullable = false)
    private long feeSum;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @EqualsAndHashCode
    @NoArgsConstructor
    public static class Key implements Serializable {
        private long riderId;
        private LocalDate settleDate;
    }
}
