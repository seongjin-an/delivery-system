package com.delivery.orderapi.domain;

import com.delivery.common.KafkaTopics;
import com.delivery.common.Times;
import com.delivery.common.dispatch.CandidateList;
import com.delivery.common.dispatch.DispatchLease;
import com.delivery.common.dispatch.OfferBoard;
import com.delivery.common.dispatch.OfferState;
import com.delivery.common.dispatch.RiderLock;
import com.delivery.common.dispatch.RiderState;
import com.delivery.common.event.OrderStatusChanged;
import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import com.delivery.orderapi.outbox.OutboxAppender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * OR-05 주문 취소.
 *
 * <p>DB 만 CANCELLED 로 바꾸면 끝나지 않는다. 배차는 레디스와 래빗엠큐 위에서 따로 돌고 있어서, 그쪽에
 * 알리지 않으면 이미 취소된 주문에 제안이 계속 나가고 라이더가 붙잡힌다. 그래서 세 가지를 한다.
 *
 * <ol>
 *   <li>배차 리스(lock:dispatch)를 먼저 잡는다. dispatch-engine 이나 offer-relay 가 지금 이 주문에 제안을 보내는
 *       중이면 끝날 때까지 기다린다. 안 잡고 하면 "보드를 CANCELLED 로 바꿨는데 방금 떠난 dispatch-engine 이
 *       OFFERED 로 덮어쓰며 제안을 보낸다" 가 된다. offer-relay 가 재제안할 때 리스를 잡는 것과 같은 이유다.</li>
 *   <li>DB 를 CANCELLED 로 바꾸고 timeline 과 order.status 를 남긴다 (한 트랜잭션).</li>
 *   <li>레디스를 정리한다. 제안 보드를 CANCELLED 로 바꾸고(이후 만료 메시지와 수락이 전부 여기서 막힌다),
 *       제안을 들고 있거나 배달 중이던 라이더를 풀어준다.</li>
 * </ol>
 *
 * <p>수락(DE-04)은 리스를 안 잡는다. 수락과 취소가 겹치는 경우는 cancel-offer.lua 와 OfferResponseService 의
 * 재확인이 나눠서 막는다 (거기 주석 참고).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCancelService {

    /** DELIVERED 와 FAILED 는 이미 끝난 주문이라 취소할 게 없다. CANCELLED 는 재요청으로 보고 200 을 준다 */
    static final Set<OrderStatus> CANCELLABLE =
            EnumSet.of(OrderStatus.CREATED, OrderStatus.DISPATCHING, OrderStatus.ASSIGNED, OrderStatus.PICKED_UP);

    /**
     * 리스를 이만큼까지 기다린다. 제안 한 번 보내는 데 보통 수 ms 인데, 브로커 확인이 늦으면 한 후보에 5초까지 걸린다.
     * 그보다 오래 기다리게 하면 손님 화면이 멈춘 것처럼 보여서, 3초 넘으면 503 을 주고 다시 누르게 한다.
     */
    static final Duration LEASE_WAIT = Duration.ofSeconds(3);
    private static final long LEASE_RETRY_MILLIS = 50;

    private final OrderRepository orderRepository;
    private final OrderStatusRecorder statusRecorder;
    private final OutboxAppender outboxAppender;
    private final TransactionTemplate transactionTemplate;

    private final DispatchLease dispatchLease;
    private final OfferBoard offerBoard;
    private final RiderState riderState;
    private final RiderLock riderLock;
    private final CandidateList candidateList;

    /** @param changed 이번 요청으로 취소됐으면 true, 이미 취소돼 있었으면 false */
    public record Cancelled(long orderId, boolean changed) {
    }

    private record DbResult(Long riderId, boolean changed) {
    }

    public Cancelled cancel(long orderId) {
        String owner = "order-api-cancel:" + UUID.randomUUID();
        acquireLease(orderId, owner);
        try {
            DbResult db = transactionTemplate.execute(tx -> cancelInDb(orderId));
            releaseDispatch(orderId, db.riderId());
            return new Cancelled(orderId, db.changed());
        } finally {
            if (!dispatchLease.release(orderId, owner)) {
                // 15초 TTL 이 지나 남이 가져간 상태에서 정리를 했다는 뜻이다. 그 사이 제안이 나갔을 수 있다
                log.warn("취소하다 배차 리스를 잃었다. 제안이 겹쳤는지 봐야 한다: orderId={}", orderId);
            }
        }
    }

    private void acquireLease(long orderId, String owner) {
        long deadline = System.nanoTime() + LEASE_WAIT.toNanos();
        while (dispatchLease.acquire(orderId, owner) == null) {
            if (System.nanoTime() > deadline) {
                throw new BusinessException(ErrorCode.DISPATCH_BUSY);
            }
            try {
                Thread.sleep(LEASE_RETRY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.DISPATCH_BUSY);
            }
        }
    }

    private DbResult cancelInDb(long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return new DbResult(order.getRiderId(), false);
        }
        if (!CANCELLABLE.contains(order.getStatus())) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "지금은 %s 상태라 취소할 수 없어요".formatted(order.getStatus()));
        }
        Instant now = Times.now();
        // 읽은 뒤 쓰기 전에 OR-04 가 DELIVERED 로 바꿨을 수 있어서 조건을 WHERE 에 다시 건다
        if (orderRepository.transition(orderId, CANCELLABLE, OrderStatus.CANCELLED, now) == 0) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "취소하는 사이 주문 상태가 바뀌었어요. 다시 확인해 주세요");
        }
        statusRecorder.record(orderId, OrderStatus.CANCELLED, now);
        outboxAppender.append(KafkaTopics.ORDER_STATUS, orderId, Long.toString(orderId),
                new OrderStatusChanged(orderId, order.getRiderId(), OrderStatus.CANCELLED.name(), now), now);
        return new DbResult(order.getRiderId(), true);
    }

    /**
     * 기능 정의서 OR-05 규칙 2, 3번. 이미 CANCELLED 인 재요청에서도 한다. 첫 요청이 DB 커밋 뒤 레디스에서
     * 실패했으면(손님은 500 을 받았다) 두 번째 요청이 마저 끝내야 한다. 전부 조건부라 두 번 해도 괜찮다.
     */
    private void releaseDispatch(long orderId, Long dbRiderId) {
        OfferBoard.Cancellation board = offerBoard.cancel(orderId, Times.now().toEpochMilli());

        // 제안을 들고 있던 라이더. "지금 들고 있는 제안이 이 제안일 때만" 푼다 (release-rider.lua)
        if (board.previous() == OfferState.OFFERED && board.riderId() > 0) {
            riderState.release(board.riderId(), orderId, board.offerId());
        }

        // 배달 중이던 라이더. DB 에 없으면(수락은 됐는데 OR-07 이 아직 안 채움) 보드에서 찾는다
        long deliveringRider = dbRiderId != null ? dbRiderId
                : board.previous() == OfferState.ACCEPTED ? board.riderId() : 0;
        if (deliveringRider > 0) {
            riderState.finishDelivery(deliveringRider, orderId);
            riderLock.release(deliveringRider, orderId);
        }

        candidateList.clear(orderId);
        log.info("주문 취소: orderId={} 보드는 {} 였다, 라이더={}", orderId, board.previous(),
                deliveringRider > 0 ? deliveringRider : board.riderId());
    }
}
