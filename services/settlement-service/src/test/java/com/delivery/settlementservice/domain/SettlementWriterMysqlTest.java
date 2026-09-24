package com.delivery.settlementservice.domain;

import com.delivery.common.Ids;
import com.delivery.common.event.DeliveryCompleted;
import com.delivery.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SE-01 을 진짜 MySQL 에 돌려본다. INSERT IGNORE 가 몇 행을 돌려주는지, AS incoming 문법이 먹는지는
 * DB 가 답해줘야 안다. H2 는 둘 다 MySQL 과 다르게 군다.
 *
 * <p>개발용 MySQL 을 같이 쓴다. @DataJpaTest 가 테스트마다 롤백하고, 라이더 아이디를 매번 새로 뽑는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SettlementWriter.class, SettlementQuery.class})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:33306/delivery?serverTimezone=UTC&characterEncoding=UTF-8",
        "spring.datasource.username=dev_user",
        "spring.datasource.password=dev_password",
        "spring.jpa.hibernate.ddl-auto=update"
})
class SettlementWriterMysqlTest {

    /** 2026-09-23 15:30 UTC = 2026-09-24 00:30 KST */
    private static final Instant JUST_AFTER_KST_MIDNIGHT = Instant.parse("2026-09-23T15:30:00Z");

    @Autowired
    private SettlementWriter writer;

    @Autowired
    private SettlementQuery query;

    @BeforeAll
    static void requireMysql() {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress("localhost", 33306), 300);
        } catch (IOException e) {
            assumeTrue(false, "MySQL(localhost:33306)이 없어서 건너뛴다. ./scripts/start.sh 로 띄우면 돈다");
        }
    }

    @Test
    void sameDeliveryTwiceIsCountedOnce() {
        long rider = Ids.newId();
        DeliveryCompleted event = delivery(Ids.newId(), rider, 2300, JUST_AFTER_KST_MIDNIGHT);

        assertThat(writer.record(event)).isTrue();
        assertThat(writer.record(event)).isFalse();

        SettlementQuery.Summary summary = query.find(rider, LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 24));
        assertThat(summary.orderCount()).isEqualTo(1);
        assertThat(summary.feeSum()).isEqualTo(4500);
        assertThat(summary.distanceSum()).isEqualTo(2300);
    }

    @Test
    void settlesOnKoreanDate() {
        // UTC 로 자르면 9월 23일이 된다. 라이더 입장에선 24일 새벽에 한 배달이다
        long rider = Ids.newId();
        writer.record(delivery(Ids.newId(), rider, 1000, JUST_AFTER_KST_MIDNIGHT));

        assertThat(query.find(rider, LocalDate.of(2026, 9, 23), LocalDate.of(2026, 9, 23)).days()).isEmpty();
        assertThat(query.find(rider, LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 24)).orderCount()).isEqualTo(1);
    }

    @Test
    void addsUpDeliveriesOfTheSameDayAndSplitsDays() {
        long rider = Ids.newId();
        writer.record(delivery(Ids.newId(), rider, 1000, JUST_AFTER_KST_MIDNIGHT));
        writer.record(delivery(Ids.newId(), rider, 2300, JUST_AFTER_KST_MIDNIGHT.plusSeconds(3600)));
        writer.record(delivery(Ids.newId(), rider, 1500, JUST_AFTER_KST_MIDNIGHT.plusSeconds(86_400)));

        SettlementQuery.Summary summary = query.find(rider, LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25));

        assertThat(summary.days()).extracting(SettlementQuery.Day::orderCount).containsExactly(2, 1);
        assertThat(summary.days().get(0).feeSum()).isEqualTo(3000 + 4500);
        assertThat(summary.feeSum()).isEqualTo(3000 + 4500 + 3500);
    }

    @Test
    void brokenEventIsRejectedInsteadOfSilentlyInserted() {
        // IGNORE 가 null 을 기본값으로 바꿔 넣기 전에 막아야 한다
        DeliveryCompleted noZone = new DeliveryCompleted(Ids.newId(), Ids.newId(), null, 18000, 1000,
                JUST_AFTER_KST_MIDNIGHT, JUST_AFTER_KST_MIDNIGHT, 0);

        assertThatThrownBy(() -> writer.record(noZone)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsReversedRange() {
        assertThatThrownBy(() -> query.find(1L, LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 24)))
                .isInstanceOf(BusinessException.class);
    }

    private static DeliveryCompleted delivery(long orderId, long riderId, int meters, Instant completedAt) {
        return new DeliveryCompleted(orderId, riderId, "Z3756_12697", 18000, meters,
                completedAt.minusSeconds(600), completedAt, 600);
    }
}
