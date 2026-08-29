package com.delivery.orderapi.outbox;

import com.delivery.common.Times;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * CDC 모드에서 다 쓴 아웃박스 행을 치운다.
 *
 * <p>폴러 때는 발행하고 나서도 행을 남겨뒀다. {@code published_at} 이 "언제 나갔나" 를 알려주는
 * 기록이었으니까. 그런데 Debezium 은 테이블이 아니라 binlog 를 읽는다. INSERT 가 binlog 에
 * 적히는 순간 이 행이 할 일은 끝난다 — 행을 지워도 이미 적힌 binlog 는 그대로다.
 *
 * <p>그래서 안 치우면 그냥 계속 쌓이기만 한다. 확장 실험에서 초당 200 주문을 흘려볼 텐데
 * 하루면 1700만 행이다.
 *
 * <p>바로 안 지우고 한 시간을 기다리는 건 사람을 위한 것이다. 뭔가 이상할 때
 * "이 주문 이벤트가 아웃박스에 들어가긴 했나" 를 눈으로 확인할 창을 남겨둔다.
 * Debezium 은 몇 밀리초 안에 읽어가므로 한 시간은 아주 넉넉한 여유다.
 */
@Component
@ConditionalOnProperty(name = "delivery.outbox.mode", havingValue = "CDC", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxPurger {

    private static final Logger log = LoggerFactory.getLogger(OutboxPurger.class);

    private final OutboxRepository outboxRepository;

    @Value("${delivery.outbox.purge-after-minutes:60}")
    private long purgeAfterMinutes;

    @Transactional
    @Scheduled(
            fixedDelayString = "${delivery.outbox.purge-interval-ms:60000}",
            initialDelayString = "${delivery.outbox.purge-interval-ms:60000}")
    public void purge() {
        try {
            Instant cutoff = Times.now().minus(Duration.ofMinutes(purgeAfterMinutes));
            int deleted = outboxRepository.deleteCreatedBefore(cutoff);
            if (deleted > 0) {
                log.info("아웃박스 {}행 정리 (기준: {} 이전)", deleted, cutoff);
            }
        } catch (Exception e) {
            // 폴러와 같은 이유다. 예외가 새어 나가면 스프링 스케줄러가 이 작업을 다시 안 돌린다.
            log.error("아웃박스 정리가 실패했다. 다음 주기에 다시 시도한다", e);
        }
    }
}
