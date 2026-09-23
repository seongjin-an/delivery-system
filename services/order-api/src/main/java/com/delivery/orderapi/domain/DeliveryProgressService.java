package com.delivery.orderapi.domain;

import com.delivery.common.KafkaTopics;
import com.delivery.common.Times;
import com.delivery.common.dispatch.CandidateList;
import com.delivery.common.dispatch.OfferBoard;
import com.delivery.common.dispatch.OfferSnapshot;
import com.delivery.common.dispatch.RiderLock;
import com.delivery.common.dispatch.RiderState;
import com.delivery.common.event.DeliveryCompleted;
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

/**
 * OR-03 픽업, OR-04 배달 완료.
 *
 * <p>둘 다 라이더 앱이 부른다. 지하 주차장에서 누르고 응답을 못 받으면 앱은 다시 보낸다.
 * 그래서 두 번째 요청에 200 을 주는 멱등 처리가 규칙의 절반이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryProgressService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final OrderStatusRecorder statusRecorder;
    private final OutboxAppender outboxAppender;
    private final TransactionTemplate transactionTemplate;

    private final RiderState riderState;
    private final RiderLock riderLock;
    private final OfferBoard offerBoard;
    private final CandidateList candidateList;

    /** @param changed 이번 요청으로 바뀌었으면 true, 이미 그 상태여서 넘어갔으면 false */
    public record Progress(long orderId, OrderStatus status, boolean changed) {
    }

    /**
     * OR-03 픽업.
     *
     * <p>상태 변경, timeline, order.status 발행을 한 트랜잭션에 넣는다. order.status 를 카프카로 직접
     * 보내지 않고 아웃박스에 넣는 이유는 OR-01 과 같다. 커밋은 됐는데 발행이 실패하거나, 발행은 됐는데
     * 롤백되는 순간이 없어진다.
     */
    public Progress pickUp(long orderId, long riderId) {
        return transactionTemplate.execute(tx -> {
            Instant now = Times.now();
            int changed = orderRepository.advance(orderId, riderId, OrderStatus.ASSIGNED, OrderStatus.PICKED_UP, now);
            if (changed == 0) {
                return explainNoChange(orderId, riderId, OrderStatus.PICKED_UP);
            }
            statusRecorder.record(orderId, OrderStatus.PICKED_UP, now);
            outboxAppender.append(KafkaTopics.ORDER_STATUS, orderId, Long.toString(orderId),
                    new OrderStatusChanged(orderId, riderId, OrderStatus.PICKED_UP.name(), now), now);
            return new Progress(orderId, OrderStatus.PICKED_UP, true);
        });
    }

    /**
     * OR-04 배달 완료.
     *
     * <p>DB 는 트랜잭션 안에서, 레디스 정리는 <b>커밋이 끝난 다음에</b> 한다. 순서가 반대면
     * 라이더를 IDLE 로 풀어준 뒤 DB 커밋이 실패하는 순간이 생긴다. 그러면 주문은 PICKED_UP 인데
     * 라이더는 새 주문을 받고, 앱이 완료를 다시 눌러도 레디스엔 이미 다른 주문이 들어가 있다.
     *
     * <p>레디스 정리는 이미 DELIVERED 인 재요청에서도 한다. 첫 요청이 커밋까지 가고 레디스에서
     * 실패했다면(라이더는 500 을 받았다) 두 번째 요청이 마저 끝내야 한다. 정리 자체가 "내 주문일 때만"
     * 건드리게 짜여 있어서 두 번 해도 괜찮다.
     */
    public Progress complete(long orderId, long riderId) {
        Progress progress = transactionTemplate.execute(tx -> {
            Instant now = Times.now();
            int changed = orderRepository.advance(orderId, riderId, OrderStatus.PICKED_UP, OrderStatus.DELIVERED, now);
            if (changed == 0) {
                return explainNoChange(orderId, riderId, OrderStatus.DELIVERED);
            }
            statusRecorder.record(orderId, OrderStatus.DELIVERED, now);
            outboxAppender.append(KafkaTopics.DELIVERY_COMPLETED, orderId, Long.toString(orderId),
                    deliveryCompleted(orderId, now), now);
            outboxAppender.append(KafkaTopics.ORDER_STATUS, orderId, Long.toString(orderId),
                    new OrderStatusChanged(orderId, riderId, OrderStatus.DELIVERED.name(), now), now);
            return new Progress(orderId, OrderStatus.DELIVERED, true);
        });

        releaseRider(orderId, riderId);
        return progress;
    }

    /**
     * 기능 정의서 OR-04 규칙 2, 3, 4번. 순서는 라이더 상태 → 찜 → 제안 흔적이다.
     *
     * <p>라이더 상태를 제일 먼저 푸는 이유: 셋 중 하나만 성공하고 죽는다면 이게 성공한 쪽이 낫다.
     * 찜(lock:rider)은 수락 뒤 12초면 이미 저절로 풀려 있고, 제안 보드와 후보 목록은 TTL 이 있다.
     * 상태만 DELIVERING 으로 남으면 그 라이더는 앱을 껐다 켜도 영영 새 콜을 못 받는다.
     */
    private void releaseRider(long orderId, long riderId) {
        boolean freed = riderState.finishDelivery(riderId, orderId);
        riderLock.release(riderId, orderId);

        // 보드가 이미 TTL 로 사라졌으면 null 이다. 역인덱스(by-id)를 같이 지우려면 offerId 가 있어야 해서 먼저 읽는다.
        OfferSnapshot offer = offerBoard.read(orderId);
        if (offer != null) {
            offerBoard.clear(orderId, offer.offerId());
        }
        candidateList.clear(orderId);

        if (!freed) {
            // 재요청이면 정상이다. 첫 요청이 이미 풀어줬고, 그 뒤 새 제안을 받았을 수도 있다.
            log.debug("라이더가 이 주문으로 배달 중이 아니라 상태는 안 건드렸다: orderId={} riderId={}", orderId, riderId);
        }
    }

    private DeliveryCompleted deliveryCompleted(long orderId, Instant completedAt) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        Instant assignedAt = historyRepository
                .findFirstByOrderIdAndStatusOrderByIdAsc(orderId, OrderStatus.ASSIGNED)
                .map(OrderStatusHistory::getOccurredAt)
                .orElseGet(() -> {
                    // OR-07 이 붙기 전에 배차된 주문이면 ASSIGNED 기록이 없다. 정산은 이 값을 안 쓰니까
                    // 멈추지 않고 접수 시각으로 채운다. 그 대신 elapsedSeconds 가 길게 잡힌다.
                    log.warn("ASSIGNED 기록이 없어서 접수 시각으로 채운다: orderId={}", orderId);
                    return order.getCreatedAt();
                });
        return new DeliveryCompleted(orderId, order.getRiderId(), order.getZoneId(),
                order.getPriceKrw(), order.getDistanceMeters(),
                assignedAt, completedAt, Duration.between(assignedAt, completedAt).toSeconds());
    }

    /**
     * 영향 행 수가 0 일 때 이유를 가려낸다. 기능 정의서 OR-03 예외 표.
     *
     * <p>순서가 중요하다. 라이더가 다르면 상태가 뭐든 403 이다. 남의 주문이 이미 PICKED_UP 이라고
     * 200 을 주면, 다른 라이더 앱에는 "픽업 완료" 가 뜨는데 실제로는 아무 일도 안 일어난 거다.
     */
    private Progress explainNoChange(long orderId, long riderId, OrderStatus target) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        if (order.getRiderId() != null && order.getRiderId() != riderId) {
            throw new BusinessException(ErrorCode.NOT_YOUR_ORDER);
        }
        if (order.getStatus() == target) {
            return new Progress(orderId, target, false);
        }
        throw new BusinessException(ErrorCode.INVALID_STATE,
                "지금은 %s 상태라 %s 로 바꿀 수 없어요".formatted(order.getStatus(), target));
    }
}
