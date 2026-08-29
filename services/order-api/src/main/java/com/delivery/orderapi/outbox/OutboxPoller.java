package com.delivery.orderapi.outbox;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 200ms 마다 아웃박스를 들여다본다. OR-06.
 *
 * <p>실제 일은 OutboxRelay 가 한다. 스케줄러와 트랜잭션을 굳이 다른 빈으로 나눈 이유는,
 * 같은 클래스 안에서 @Transactional 메서드를 부르면 프록시를 안 거쳐서 트랜잭션이 안 걸리기
 * 때문이다. 그러면 SELECT ... FOR UPDATE 가 즉시 커밋돼서 잠금이 하나도 유지되지 않는다.
 * 조용히 잘못되는 종류라 아예 구조로 막아뒀다.
 *
 * <p>fixedDelay 를 쓴다. fixedRate 로 하면 한 번이 200ms 를 넘겼을 때 다음 실행이 바로 겹쳐서
 * 밀린 만큼 계속 몰아친다. fixedDelay 는 "끝나고 나서 200ms" 라 그럴 일이 없다.
 */
@Component
@RequiredArgsConstructor
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxRelay outboxRelay;

    @Value("${delivery.outbox.batch-size:100}")
    private int batchSize;

    @Scheduled(
            fixedDelayString = "${delivery.outbox.poll-interval-ms:200}",
            initialDelayString = "${delivery.outbox.poll-interval-ms:200}")
    public void poll() {
        try {
            int published = outboxRelay.relayBatch(batchSize);
            if (published > 0) {
                log.debug("아웃박스 {}건 발행", published);
            }
        } catch (Exception e) {
            // 여기서 예외가 새어 나가면 스프링 스케줄러가 이 작업을 아예 다시 안 돌린다.
            // 그러면 아웃박스가 조용히 쌓이기만 하고 아무도 모른다. 반드시 잡아서 삼킨다.
            log.error("아웃박스 폴링이 실패했다. 다음 주기에 다시 시도한다", e);
        }
    }
}
