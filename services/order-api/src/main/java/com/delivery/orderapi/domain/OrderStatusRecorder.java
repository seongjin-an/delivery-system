package com.delivery.orderapi.domain;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 상태가 바뀔 때 기록 한 줄을 남긴다.
 *
 * <p>레포지토리를 직접 부르면 한 줄이라 굳이 감쌀 이유가 없어 보이는데, 이 호출을 빼먹기가
 * 아주 쉽다. OR-03 픽업, OR-04 완료, OR-05 취소, OR-07 배차 반영이 전부 상태를 바꾸는데
 * 그중 하나만 기록을 안 남기면 timeline 에 구멍이 뚫린다. 그게 하필 "왜 오래 걸렸냐" 를
 * 물어보는 그 주문일 수 있다. 이름이 있는 자리를 만들어두면 빼먹었을 때 눈에 띈다.
 *
 * <p>트랜잭션을 열지 않는다. 상태를 바꾸는 그 트랜잭션 안에 같이 들어가야
 * "상태는 바뀌었는데 기록은 없는" 상황이 안 생긴다.
 */
@Component
@RequiredArgsConstructor
public class OrderStatusRecorder {

    private final OrderStatusHistoryRepository historyRepository;

    public void record(long orderId, OrderStatus status, Instant occurredAt) {
        historyRepository.save(OrderStatusHistory.of(orderId, status, occurredAt));
    }
}
