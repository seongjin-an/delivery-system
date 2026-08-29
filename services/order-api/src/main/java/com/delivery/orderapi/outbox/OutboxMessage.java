package com.delivery.orderapi.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 아직 카프카로 못 나간 이벤트 한 줄.
 *
 * <p>왜 이런 테이블이 필요하냐면, 주문을 DB 에 넣고 나서 카프카로 발행하는 순서로 짜면 그 사이에
 * 프로세스가 죽었을 때 "DB 에는 주문이 있는데 배차는 안 걸린 주문" 이 남는다. 반대로 카프카를 먼저
 * 발행하고 DB 커밋이 실패하면 없는 주문의 배차가 걸린다. 둘 다 손으로 찾아 고쳐야 하는 사고다.
 * 주문 행과 이벤트 행을 같은 트랜잭션에 넣으면 그 갈림길 자체가 없어진다.
 *
 * <p>PK 를 자동증가 정수로 둔 건 주문과 반대 이유다. 주문 아이디(TSID)도 시간순으로 늘어나긴
 * 하지만 같은 밀리초 안에서는 순서가 뒤섞인다. 폴러는 "먼저 들어온 게 반드시 작은 번호" 라야
 * 오래된 것부터 정확히 집어갈 수 있어서, 여기는 DB 가 매겨주는 번호가 맞다.
 */
@Entity
@Table(name = "outbox", indexes = {
        // 폴러는 항상 "아직 안 나간 것" 만 오래된 순으로 찾는다. 이 인덱스가 없으면
        // 발행이 끝난 행까지 전부 훑게 되고, 테이블이 커질수록 폴링이 느려진다.
        @Index(name = "idx_outbox_unpublished", columnList = "published_at, id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 어느 주문의 이벤트인지. 장애 났을 때 이 컬럼으로 찾는다 */
    @Column(name = "aggregate_id", nullable = false)
    private long aggregateId;

    @Column(name = "destination_topic", length = 100, nullable = false)
    private String destinationTopic;

    /**
     * 카프카 키로 그대로 쓴다. 같은 주문의 이벤트가 같은 파티션에 들어가 순서가 지켜진다.
     *
     * <p>아이디는 long 인데 여기만 문자열인 게 어색해 보이지만, 카프카 키는 결국 바이트열이고
     * 우리 프로듀서가 StringSerializer 를 쓴다. 확장 실험 B-1 에서 파티션 키를 존 단위로 바꿔볼
     * 예정이라, 숫자 아닌 값이 들어올 자리를 열어두는 쪽이 낫다.
     */
    @Column(name = "partition_key", length = 64, nullable = false)
    private String partitionKey;

    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 발행에 성공한 뒤에만 채운다. null 이면 아직 안 나간 것 */
    @Column(name = "published_at")
    private Instant publishedAt;

    /** 발행을 몇 번 시도했는지. 10회를 넘으면 폴러가 경고를 남긴다 (OR-06) */
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    private OutboxMessage(long aggregateId, String destinationTopic, String partitionKey,
                          String payload, Instant now) {
        this.aggregateId = aggregateId;
        this.destinationTopic = destinationTopic;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.createdAt = now;
        this.attemptCount = 0;
    }

    public static OutboxMessage pending(long aggregateId, String destinationTopic,
                                        String partitionKey, String payload, Instant now) {
        return new OutboxMessage(aggregateId, destinationTopic, partitionKey, payload, now);
    }

    /** 카프카가 받았다고 확인해준 뒤에만 부른다. 이걸 채우면 폴러가 다시 안 집어간다 */
    public void markPublished(Instant publishedAt) {
        this.publishedAt = publishedAt;
        this.attemptCount++;
    }

    /**
     * 발행이 안 됐다. published_at 은 그대로 두니까 다음 주기에 다시 집어간다.
     *
     * <p>여기서 포기하지 않는 게 중요하다. 카프카가 잠깐 안 붙은 거면 다음 주기에 그냥 되는데,
     * 몇 번 실패했다고 버려버리면 "DB 엔 주문이 있는데 배차가 안 걸린" 주문이 생긴다.
     * 아웃박스를 쓰는 이유가 통째로 사라지는 셈이라, 세기만 하고 계속 다시 시도한다.
     */
    public void markSendFailed() {
        this.attemptCount++;
    }

    public boolean isStuck(int threshold) {
        return attemptCount > threshold;
    }
}
