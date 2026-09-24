package com.delivery.settlementservice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 배달 한 건의 정산. 기능 정의서 SE-01 의 settlement_detail.
 *
 * <p>이 엔티티는 테이블을 만드는 용도로만 쓴다(ddl-auto: update). 넣는 건 SettlementWriter 가
 * INSERT IGNORE 로 한다. JPA 의 save 로는 "이미 있으면 아무것도 안 하고 0 을 돌려준다" 를 한 문장으로 못 한다.
 *
 * <p>order_id 가 PK 라는 게 리플레이 멱등성의 전부다. 같은 배달 이벤트가 두 번 오든, 오프셋을 리셋해서
 * 7일치가 다시 오든 이 행은 한 번만 생긴다.
 */
@Entity
@Table(name = "settlement_detail", indexes = {
        @Index(name = "idx_detail_rider_date", columnList = "rider_id, settle_date")
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementDetail {

    @Id
    @Column(name = "order_id", nullable = false)
    private long orderId;

    @Column(name = "rider_id", nullable = false)
    private long riderId;

    /** 한국 시각 기준 날짜. UTC 로 자르면 아침 9시 전에 끝난 배달이 전날로 간다 */
    @Column(name = "settle_date", nullable = false)
    private LocalDate settleDate;

    @Column(name = "zone_id", length = 20, nullable = false)
    private String zoneId;

    @Column(name = "price_krw", nullable = false)
    private int priceKrw;

    @Column(name = "distance_meters", nullable = false)
    private int distanceMeters;

    @Column(name = "fee_krw", nullable = false)
    private int feeKrw;

    /** 어떤 수수료 공식으로 계산했는지. 공식을 바꾸고 리플레이할 때 옛 행을 가려내려고 남긴다 (SE-03) */
    @Column(name = "fee_policy", length = 20, nullable = false)
    private String feePolicy;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}
