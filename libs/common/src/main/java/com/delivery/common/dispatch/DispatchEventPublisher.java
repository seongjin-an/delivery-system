package com.delivery.common.dispatch;

import com.delivery.common.JsonUtil;
import com.delivery.common.KafkaTopics;
import com.delivery.common.Times;
import com.delivery.common.event.DispatchAssigned;
import com.delivery.common.event.DispatchFailed;
import com.delivery.common.event.OrderStatusChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


/**
 * 배차 결과를 카프카로 알린다.
 *
 * <p>여기는 아웃박스를 안 쓴다. dispatch-engine 도 offer-relay 도 DB 를 안 쓰기 때문에
 * "DB 커밋과 발행이 갈라지는 순간" 자체가 없다. 대신 발행에 실패하면 예외가 올라가서
 * 메시지를 ack 하지 않고, 재소비돼서 처음부터 다시 한다.
 *
 * <p><b>그러려면 브로커 응답을 기다려야 한다.</b> 예전엔 send() 가 돌려주는 future 를 버리고 있었다.
 * 카프카 전송은 비동기라 그러면 실패해도 예외가 안 올라오고, 위에 적은 재소비는 한 번도 안 일어난다.
 * 라이더가 수락해서 레디스엔 ACCEPTED 가 찍혔는데 dispatch.assigned 가 조용히 안 나가면,
 * order-api 의 주문은 영원히 DISPATCHING 이다. OR-07 을 붙이면서 알아챘다.
 *
 * <p>HTTP 로 들어오는 수락(DE-04)은 이걸로 안 막혀서 레디스 아웃박스로 돌렸다. 레디스에 ACCEPTED 를 쓴 다음
 * 발행이 실패하거나 그 사이에 죽으면, 라이더가 다시 눌러도 409 라서 이벤트가 끝내 안 나간다. 2단계 실험에서
 * kill -9 한 번에 1~2건씩 실제로 났다. 그래서 수락은 {@link #assignedEvents} 로 이벤트를 만들어 수락 Lua 에 같이
 * 넘기고, 보내는 건 dispatch-engine 의 DispatchOutboxRelay 가 {@link #send} 로 한다.
 *
 * <p>두 서비스가 같이 쓰는 이유는 {@code dispatch.failed} 를 양쪽에서 발행해서다.
 * dispatch-engine 은 "반경 안에 라이더가 없다" 로, offer-relay 는 "다섯 번 제안했는데 아무도
 * 안 받았다" 로 발행한다. 각자 짜면 한쪽이 {@code reason} 을 {@code cause} 라고 적는 날이 오고,
 * 그러면 order-api 의 OR-07 컨슈머가 한쪽만 조용히 못 읽는다.
 */
@RequiredArgsConstructor
public class DispatchEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    /** 브로커가 받았다고 할 때까지 기다리는 최대 시간. acks=all 이라 평소엔 몇 ms 다 */
    private static final long SEND_TIMEOUT_SECONDS = 5;

    /** 배차가 시작됐다 (order-api 가 상태를 DISPATCHING 으로 바꾼다) */
    public void publishDispatching(long orderId) {
        publish(KafkaTopics.ORDER_STATUS, orderId,
                new OrderStatusChanged(orderId, null, "DISPATCHING", Times.now()));
    }

    /**
     * 라이더가 수락했다 (DE-04). 보내지 않고 레디스 아웃박스에 넣을 모양으로 만들기만 한다.
     *
     * <p>토픽 두 개에 나눠 보낸다. {@code dispatch.assigned} 는 "배차가 이렇게 끝났다" 는
     * 사실이고 정산·지표가 이걸 본다. {@code order.status} 는 주문 상태 흐름이라 order-api 가
     * 픽업·완료와 같은 줄에 놓고 읽는다. 한 토픽에 몰면 정산이 픽업 이벤트까지 걸러내야 한다.
     *
     * <p>static 인 건 만드는 데 카프카가 필요 없어서다. 수락 경로에서 이걸로 만들어 수락 Lua 에 넘기고,
     * 보내는 건 DispatchOutboxRelay 가 {@link #send} 로 한다.
     */
    public static List<String> assignedEvents(long orderId, long riderId, long offerId, int attempt) {
        Instant now = Times.now();
        String key = Long.toString(orderId);
        long enqueuedAt = now.toEpochMilli();
        return List.of(
                new OutboxEnvelope(KafkaTopics.DISPATCH_ASSIGNED, key,
                        JsonUtil.toJson(new DispatchAssigned(orderId, riderId, offerId, attempt, now)), enqueuedAt).toJson(),
                new OutboxEnvelope(KafkaTopics.ORDER_STATUS, key,
                        JsonUtil.toJson(new OrderStatusChanged(orderId, riderId, "ASSIGNED", now)), enqueuedAt).toJson());
    }

    /**
     * 레디스 아웃박스에서 꺼낸 걸 보내기만 하고 기다리지 않는다. 릴레이가 여러 건을 먼저 다 보내놓고 한꺼번에 기다린다.
     *
     * <p>한 건씩 보내고 기다리면 느리다. 실제로 그렇게 했더니 카프카 응답이 한 번에 수십~수백 ms 라 초당 30건을 못 따라가서,
     * 배차 확정이 최대 6.4초 늦게 나갔고 5초 뒤 픽업하러 온 라이더가 INVALID_STATE 를 받았다(45건).
     * 몰아 보내면 프로듀서가 알아서 묶어 보낸다. 같은 주문의 이벤트는 키가 같아서 한 파티션에 보낸 순서대로 들어간다.
     */
    public CompletableFuture<?> sendAsync(OutboxEnvelope envelope) {
        return kafkaTemplate.send(envelope.topic(), envelope.key(), envelope.payload());
    }

    /** 브로커 응답을 기다리는 최대 시간. 릴레이가 {@link #sendAsync} 결과를 기다릴 때 쓴다 */
    public static long sendTimeoutSeconds() {
        return SEND_TIMEOUT_SECONDS;
    }

    /** 레디스 아웃박스에서 꺼낸 걸 보낸다. 브로커가 받았다고 할 때까지 기다리고, 실패하면 예외를 올린다 */
    public void send(OutboxEnvelope envelope) {
        try {
            sendAsync(envelope).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("아웃박스 이벤트 발행 중 끊겼다: " + envelope.topic(), e);
        } catch (Exception e) {
            throw new IllegalStateException("아웃박스 이벤트 발행 실패: " + envelope.topic(), e);
        }
    }

    /**
     * 배차를 포기했다.
     *
     * @param attempt 라이더에게 실제로 간 제안 수. order-api 가 주문 조회의 attempt 로 보여준다
     */
    public void publishFailed(long orderId, int attempt, String reason) {
        publish(KafkaTopics.DISPATCH_FAILED, orderId, new DispatchFailed(orderId, attempt, reason, Times.now()));
    }

    private void publish(String topic, long orderId, Object payload) {
        // 키를 orderId 로 둬서 같은 주문의 이벤트가 한 파티션에 순서대로 들어가게 한다.
        try {
            kafkaTemplate.send(topic, Long.toString(orderId), JsonUtil.toJson(payload))
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(topic + " 발행 중에 끊겼다: orderId=" + orderId, e);
        } catch (ExecutionException | TimeoutException e) {
            // BusinessException 이 아니라서 컨슈머 쪽에서는 3회 백오프 재시도를 받는다.
            throw new IllegalStateException(topic + " 발행 실패: orderId=" + orderId, e);
        }
    }
}
