package com.delivery.orderapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 주문 한 건.
 *
 * <p>PK 를 자동증가 정수가 아니라 UUIDv7 문자열로 둔 이유가 있다. 이 아이디가 카프카 파티션 키로,
 * 레디스 키로, 래빗엠큐 메시지 안으로 그대로 흘러다닌다. DB 가 번호를 매겨줄 때까지 기다려야 하면
 * 그 앞 단계에서 아이디를 쓸 수가 없다.
 *
 * <p>distanceMeters 를 만들 때 같이 저장해둔다. 20km 제한을 검사하느라 어차피 한 번 계산하는데,
 * OR-04 의 delivery.completed 페이로드에 또 필요하다. 그때 좌표로 다시 계산하면 같은 값을 두 번
 * 구하는 셈이고, 나중에 거리 계산식을 바꾸면 접수 때와 정산 때 값이 달라진다.
 */
@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_status_created", columnList = "status, created_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @Column(name = "order_id", length = 36, nullable = false)
    private String orderId;

    @Column(name = "store_id", length = 64, nullable = false)
    private String storeId;

    @Column(name = "store_lat", nullable = false)
    private double storeLat;

    @Column(name = "store_lng", nullable = false)
    private double storeLng;

    @Column(name = "dest_lat", nullable = false)
    private double destLat;

    @Column(name = "dest_lng", nullable = false)
    private double destLng;

    /** 가게 좌표로 계산한 존. 클라이언트가 보낸 값을 믿지 않는다 (기능 정의서 3.4) */
    @Column(name = "zone_id", length = 20, nullable = false)
    private String zoneId;

    @Column(name = "price_krw", nullable = false)
    private int priceKrw;

    /** 가게에서 목적지까지 직선거리. 접수 때 한 번 계산해서 들고 있는다 */
    @Column(name = "distance_meters", nullable = false)
    private int distanceMeters;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OrderStatus status;

    /** 배차되기 전에는 비어 있다 */
    @Column(name = "rider_id", length = 36)
    private String riderId;

    /** 몇 번째 후보에서 잡혔는지. OR-07 이 채운다 */
    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private Order(String orderId, String storeId,
                  double storeLat, double storeLng, double destLat, double destLng,
                  String zoneId, int priceKrw, int distanceMeters, Instant now) {
        this.orderId = orderId;
        this.storeId = storeId;
        this.storeLat = storeLat;
        this.storeLng = storeLng;
        this.destLat = destLat;
        this.destLng = destLng;
        this.zoneId = zoneId;
        this.priceKrw = priceKrw;
        this.distanceMeters = distanceMeters;
        this.status = OrderStatus.CREATED;
        this.attempt = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Order create(String orderId, String storeId,
                               double storeLat, double storeLng, double destLat, double destLng,
                               String zoneId, int priceKrw, int distanceMeters, Instant now) {
        return new Order(orderId, storeId, storeLat, storeLng, destLat, destLng,
                zoneId, priceKrw, distanceMeters, now);
    }
}
