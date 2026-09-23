package com.delivery.orderapi.domain;

import com.delivery.common.Times;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * OR-07 배차 결과 반영. 기능 정의서 OR-07.
 *
 * <p>규칙 3번(멱등)이 전부다. 카프카는 같은 메시지를 두 번 줄 수 있고(at-least-once),
 * 서로 다른 토픽끼리는 순서도 안 맞춰준다. 그래서 "지금 상태가 이거일 때만 바꾼다" 를
 * 이벤트마다 정해두고, 거기 안 맞으면 조용히 넘어간다.
 *
 * <pre>
 * DISPATCHING ← CREATED 일 때만
 * ASSIGNED    ← CREATED, DISPATCHING 일 때만
 * FAILED      ← CREATED, DISPATCHING 일 때만
 * </pre>
 *
 * <p>ASSIGNED 에 CREATED 가 들어 있는 이유: DISPATCHING 은 order.status, ASSIGNED 는
 * dispatch.assigned 로 온다. 토픽이 달라서 ASSIGNED 가 먼저 도착할 수 있다. 1순위가 1초 만에
 * 수락하면 실제로 그럴 만하다. CREATED 에서 막으면 그 주문은 영원히 배차 중이다.
 * 그 뒤에 늦게 온 DISPATCHING 은 CREATED 가 아니라서 버려진다. 상태가 뒤로 안 간다.
 *
 * <p>CANCELLED 는 어디에도 없다. 손님이 취소한 주문에 뒤늦게 배차 확정이 와도 되살리지 않는다.
 * (그때 라이더를 놓아주는 건 OR-05 가 할 일이다)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchResultService {

    private static final Set<OrderStatus> BEFORE_DISPATCHING = EnumSet.of(OrderStatus.CREATED);
    private static final Set<OrderStatus> BEFORE_RESULT = EnumSet.of(OrderStatus.CREATED, OrderStatus.DISPATCHING);

    private final OrderRepository orderRepository;
    private final OrderStatusRecorder statusRecorder;

    @Transactional
    public boolean markDispatching(long orderId, Instant at) {
        int changed = orderRepository.transition(orderId, BEFORE_DISPATCHING, OrderStatus.DISPATCHING, Times.now());
        return afterUpdate(orderId, changed, OrderStatus.DISPATCHING, at);
    }

    @Transactional
    public boolean markAssigned(long orderId, long riderId, int attempt, Instant at) {
        int changed = orderRepository.assign(orderId, BEFORE_RESULT, riderId, attempt, Times.now());
        return afterUpdate(orderId, changed, OrderStatus.ASSIGNED, at);
    }

    @Transactional
    public boolean markFailed(long orderId, String reason, Instant at) {
        int changed = orderRepository.transition(orderId, BEFORE_RESULT, OrderStatus.FAILED, Times.now());
        if (changed == 1) {
            log.info("배차 실패: orderId={} reason={}", orderId, reason);
        }
        return afterUpdate(orderId, changed, OrderStatus.FAILED, at);
    }

    /**
     * 실제로 바뀌었을 때만 timeline 에 한 줄 남긴다. 중복 이벤트마다 적으면 같은 ASSIGNED 가 두 줄 생긴다.
     *
     * <p>시각은 이벤트에 적힌 시각이다. 우리가 받은 시각으로 적으면 컨슈머가 밀렸을 때
     * "배차 확정까지 3분 걸렸다" 처럼 보이는데, 실제로는 라이더가 8초 만에 수락했을 수 있다.
     */
    private boolean afterUpdate(long orderId, int changed, OrderStatus to, Instant at) {
        if (changed == 1) {
            statusRecorder.record(orderId, to, at != null ? at : Times.now());
            return true;
        }
        if (!orderRepository.existsById(orderId)) {
            // 없는 주문. 새 컨슈머 그룹이 처음 뜰 때 earliest 로 토픽을 처음부터 읽으면 DB 를 비우기 전
            // 주문들이 줄줄이 온다. 에러로 올려서 DLT 로 보낼 일은 아니고, 눈에 띄게만 남긴다.
            log.warn("없는 주문의 배차 결과라 버린다: orderId={} status={}", orderId, to);
        } else {
            log.debug("이미 {} 이거나 더 진행된 주문이라 넘어간다: orderId={}", to, orderId);
        }
        return false;
    }
}
