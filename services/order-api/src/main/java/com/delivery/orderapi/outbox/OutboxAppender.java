package com.delivery.orderapi.outbox;

import com.delivery.common.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 이벤트 객체를 아웃박스 한 줄로 만들어 넣는다.
 *
 * <p>따로 빼둔 이유는 OR-04(배달 완료) 도 같은 방식으로 delivery.completed 를 넣을 거라서다.
 * 직렬화를 각자 하면 어떤 이벤트는 타임스탬프가 숫자로, 어떤 건 ISO 문자열로 나가서
 * 컨슈머 쪽에서 깨진다. JsonUtil 한 곳을 거치게 묶어둔다.
 *
 * <p>여기서 트랜잭션을 열지 않는다. 부르는 쪽의 트랜잭션에 얹혀야 "주문 행과 이벤트 행이
 * 한 트랜잭션" 이 성립한다.
 */
@Component
@RequiredArgsConstructor
public class OutboxAppender {

    private final OutboxRepository outboxRepository;

    public OutboxMessage append(String topic, String aggregateId, String partitionKey,
                                Object payload, Instant now) {
        return outboxRepository.save(OutboxMessage.pending(
                aggregateId, topic, partitionKey, JsonUtil.toJson(payload), now));
    }
}
