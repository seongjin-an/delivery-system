package com.delivery.orderapi.outbox;

import com.delivery.common.Times;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 아웃박스에 쌓인 이벤트를 카프카로 내보낸다. OR-06.
 *
 * <p>한 트랜잭션 안에서 잠그고 → 보내고 → 표시한다. 잠금을 쥔 채로 카프카를 기다리는 게
 * 좀 아깝게 보이는데, 그게 바로 SKIP LOCKED 를 쓰는 이유다. 다른 인스턴스는 이 행들을
 * 건너뛰고 그다음 걸 가져가니까 서로 기다릴 일이 없다.
 *
 * <p>보내는 순서도 신경 썼다. 100건을 한 건씩 "보내고 확인" 하면 왕복이 100번이라 200ms 주기를
 * 못 맞춘다. 그래서 일단 다 던져놓고(send 는 안 기다린다) flush 로 한 번에 내보낸 다음,
 * 그때부터 확인만 돌린다. 카프카 프로듀서가 알아서 배치로 묶어준다.
 */
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** 카프카 ack 를 기다리는 한도. 폴링 주기보다 넉넉해야 브로커가 잠깐 느릴 때 헛되이 실패하지 않는다 */
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(5);

    /** 이 횟수를 넘게 실패하면 경고를 남긴다 (OR-06 규칙 3번) */
    private static final int STUCK_THRESHOLD = 10;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * @return 이번에 실제로 발행한 건수
     */
    @Transactional
    public int relayBatch(int batchSize) {
        List<OutboxMessage> batch = outboxRepository.lockUnpublished(batchSize);
        if (batch.isEmpty()) {
            return 0;
        }

        List<CompletableFuture<SendResult<String, String>>> sending = new ArrayList<>(batch.size());
        for (OutboxMessage message : batch) {
            sending.add(kafkaTemplate.send(
                    message.getDestinationTopic(), message.getPartitionKey(), message.getPayload()));
        }
        kafkaTemplate.flush();

        Instant now = Times.now();
        int published = 0;
        for (int i = 0; i < batch.size(); i++) {
            OutboxMessage message = batch.get(i);
            if (confirm(sending.get(i), message)) {
                message.markPublished(now);
                published++;
            }
        }

        if (published < batch.size()) {
            log.warn("아웃박스 발행 일부 실패: {}건 중 {}건만 나갔다", batch.size(), published);
        }
        return published;
    }

    private boolean confirm(CompletableFuture<SendResult<String, String>> future, OutboxMessage message) {
        try {
            future.get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            // 인터럽트를 삼키면 종료 신호가 사라진다. 다시 세워두고 실패로 친다.
            Thread.currentThread().interrupt();
            message.markSendFailed();
            return false;
        } catch (Exception e) {
            message.markSendFailed();
            if (message.isStuck(STUCK_THRESHOLD)) {
                // 여기까지 왔으면 브로커가 잠깐 흔들린 게 아니라 뭔가 잘못된 것이다.
                // (토픽이 없다거나, 메시지가 max.request.size 를 넘는다거나)
                log.error("아웃박스 {}번이 {}회째 못 나가고 있다: topic={} 원인={}",
                        message.getId(), message.getAttemptCount(), message.getDestinationTopic(),
                        e.toString());
            } else {
                log.warn("아웃박스 {}번 발행 실패({}회째), 다음 주기에 다시 시도한다: {}",
                        message.getId(), message.getAttemptCount(), e.toString());
            }
            return false;
        }
    }
}
