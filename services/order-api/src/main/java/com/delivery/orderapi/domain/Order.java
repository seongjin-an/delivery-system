package com.delivery.orderapi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * 주문 한 건.
 *
 * <p>PK 는 TSID(BIGINT) 다. DB 의 AUTO_INCREMENT 를 안 쓴 이유는, 이 아이디가 카프카 파티션 키로
 * 레디스 키로 래빗엠큐 메시지 안으로 그대로 흘러다녀서다. DB 가 번호를 매겨줄 때까지 기다려야 하면
 * 그 앞 단계에서 아이디를 쓸 수가 없다.
 *
 * <p>Persistable 을 구현한 건 스프링 데이터의 습성 때문이다. 아이디를 우리가 직접 넣으면
 * save() 가 "이미 있는 행인가?" 를 확인하려고 SELECT 를 한 번 날리고 나서 INSERT 한다.
 * 주문 한 건마다 쓸데없는 쿼리가 하나씩 붙는 셈이라, isNew 를 직접 알려줘서 바로 INSERT 하게 한다.
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
public class Order implements Persistable<Long> {

    @Id
    @Column(name = "order_id", nullable = false)
    private long orderId;

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
    @Column(name = "rider_id")
    private Long riderId;

    /** 몇 번째 후보에서 잡혔는지. OR-07 이 채운다 */
    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 위 주석에 적은 SELECT 를 없애는 스위치. DB 에는 안 들어간다 */
    @Transient
    private boolean brandNew = true;

    private Order(long orderId, String storeId,
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

    public static Order create(long orderId, String storeId,
                               double storeLat, double storeLng, double destLat, double destLng,
                               String zoneId, int priceKrw, int distanceMeters, Instant now) {
        return new Order(orderId, storeId, storeLat, storeLng, destLat, destLng,
                zoneId, priceKrw, distanceMeters, now);
    }

    @Override
    public Long getId() {
        return orderId;
    }

    @Override
    public boolean isNew() {
        return brandNew;
    }

    /** 한 번 저장됐거나 DB 에서 읽어온 뒤로는 새 것이 아니다 */
    @PostPersist
    @PostLoad
    void markNotNew() {
        this.brandNew = false;
    }
}
