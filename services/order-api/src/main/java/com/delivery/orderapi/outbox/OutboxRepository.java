package com.delivery.orderapi.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    /**
     * 아직 안 나간 것들을 오래된 순으로 집어오면서 그 행에 잠금을 건다. OR-06 규칙 4번.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} 가 핵심이다. order-api 를 두 대 띄우면 둘 다 200ms 마다
     * 같은 테이블을 들여다보는데, 그냥 SELECT 하면 같은 행을 둘 다 집어가서 카프카에 같은 이벤트가
     * 두 번 나간다. {@code FOR UPDATE} 만 걸면 뒤에 온 놈이 앞사람이 끝날 때까지 서서 기다린다 —
     * 두 대로 늘린 의미가 없어진다. {@code SKIP LOCKED} 는 "잠긴 건 건너뛰고 그다음 걸 가져와" 라서
     * 두 인스턴스가 서로 다른 행을 나눠 갖는다.
     *
     * <p>JPQL 로는 SKIP LOCKED 를 힌트로 주는 방법이 있지만 방언마다 되고 안 되고가 갈린다.
     * 여기는 실행되는 SQL 이 눈에 보이는 게 중요한 자리라 네이티브로 적었다.
     */
    @Query(value = """
            SELECT * FROM outbox
            WHERE published_at IS NULL
            ORDER BY id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxMessage> lockUnpublished(@Param("batchSize") int batchSize);
}
