package com.delivery.common.dispatch;

import com.delivery.common.Ids;
import com.delivery.common.RabbitTopology;
import com.delivery.common.Times;
import com.delivery.common.event.DispatchOffer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * DE-03 제안 발송. 후보 목록에서 하나씩 꺼내 라이더를 찜하고 래빗엠큐로 제안을 던진다.
 *
 * <p>dispatch-engine 의 첫 제안과 offer-relay 의 재제안이 <b>글자 하나까지 같은 일</b>이라
 * libs/common 에 둔다. 다른 점은 시작 attempt 번호뿐이다.
 */
@RequiredArgsConstructor
public class OfferSender {

    private static final Logger log = LoggerFactory.getLogger(OfferSender.class);

    /** publisher confirm 을 기다리는 한도 */
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final RabbitTemplate rabbitTemplate;
    private final OfferBoard offerBoard;
    private final CandidateList candidateList;
    private final RiderLock riderLock;
    private final RiderState riderState;

    /**
     * 후보를 하나씩 꺼내면서 찜에 성공한 첫 라이더에게 제안을 보낸다.
     *
     * @param attempt 이 제안이 몇 번째인지. 첫 배차면 1, 재제안이면 직전 attempt + 1
     * @return 보냈으면 그 제안, 후보가 다 떨어졌으면 null
     */
    public DispatchOffer offerToNextCandidate(long orderId, int attempt) {
        Long next;

        while ((next = candidateList.next(orderId)) != null) {
            long riderId = next;

            if (!riderLock.claim(riderId, orderId)) {
                // 다른 주문이 방금 이 라이더를 채갔다. 다음 후보로 간다.
                log.debug("라이더 {} 는 이미 찜돼 있다. 다음 후보로", riderId);
                continue;
            }

            DispatchOffer offer = publish(orderId, riderId, attempt);
            if (offer != null) {
                return offer;
            }
            // 발행이 실패했으면 다음 후보로 넘어간다. 찜과 상태를 되돌리는 건 publish 안에서
            // 이미 했다 — 되돌릴 게 둘(찜, 라이더 상태)이라 한 군데서 같이 하는 게 맞다.
            //
            // attempt 를 안 올리는 게 중요하다. 브로커가 안 받았으면 라이더는 제안을 못 본 거라
            // "몇 번째 제안인지" 를 세는 숫자가 올라가면 안 된다. 올리면 이런 일이 난다 —
            // 후보 10명 중 셋이 발행에 실패하고 넷째가 성공하면 attempt 가 4로 기록되는데,
            // 실제로 라이더에게 간 제안은 1건뿐이다. 그 상태에서 두 번 만료되면 후보가 여섯 명
            // 남았는데도 max-attempts 5에 걸려서 배차 실패로 끝난다.
        }
        return null;
    }

    private DispatchOffer publish(long orderId, long riderId, int attempt) {
        requirePublisherConfirms();

        // 재제안할 때마다 새 offerId 를 만든다. 펜싱 규칙(기능 정의서 3.9)이 이걸로 판정한다 —
        // 옛 타이머 메시지가 뒤늦게 도착해도 offerId 가 달라서 버려진다.
        long offerId = Ids.newId();
        Instant offeredAt = Times.now();

        offerBoard.writeOffered(orderId, offerId, riderId, attempt, offeredAt.toEpochMilli());
        riderState.markOffered(riderId, offerId);

        DispatchOffer offer = new DispatchOffer(offerId, orderId, riderId, attempt, offeredAt);
        CorrelationData confirm = new CorrelationData(Long.toString(offerId));

        // 익스체인지에 한 번만 발행한다. 알림 큐와 타이머 큐 양쪽에 브로커가 복제해준다.
        // 코드에서 두 번 발행하면 한쪽만 성공하는 경우가 생기는데, 그게 둘 다 사고다 —
        // 알림만 가면 안 받았을 때 아무도 모르고, 타이머만 있으면 라이더는 제안이 온 줄도
        // 모르는데 10초 뒤에 거절한 걸로 처리된다.
        rabbitTemplate.convertAndSend(
                RabbitTopology.DISPATCH_EXCHANGE, RabbitTopology.RK_OFFER_CREATED, offer, confirm);

        if (!confirmed(confirm, orderId, riderId)) {
            // 브로커가 못 받았다. 되돌려놓고 다음 후보로 간다. 안 되돌리면 보드에 OFFERED 가
            // 남아서, 아무한테도 안 간 제안을 다음 시도가 "진행 중" 으로 오해한다.
            offerBoard.clear(orderId, offerId);
            riderState.release(riderId, orderId, offerId);
            return null;
        }

        log.info("제안 발송: orderId={} riderId={} offerId={} attempt={}",
                orderId, riderId, offerId, attempt);
        return offer;
    }

    /**
     * publisher confirm 이 꺼져 있으면 여기서 바로 끝낸다.
     *
     * <p>실제로 한 번 당했다. dispatch-engine yml 에만 {@code publisher-confirm-type: correlated}
     * 가 있고 offer-relay 에는 없었는데, 이 클래스를 공용으로 옮기면서 그 전제가 같이 안 따라왔다.
     * 증상이 고약했다 — 확인 future 가 영영 안 끝나서 후보 한 명당 5초씩 타임아웃이 나고,
     * 실패로 판정해 다음 후보로 넘어간다. 그래서 라이더 6명이 25초 만에 전부 타버리고
     * 배차가 실패했다. 로그에는 "제안 발행 확인 실패" 만 찍혀서 브로커 문제처럼 보인다.
     *
     * <p>기동할 때 막지 않고 여기서 막는 이유는 notification-worker 다. 걔도 레디스와
     * 래빗엠큐를 둘 다 써서 이 빈이 같이 만들어지는데, 제안을 보낼 일은 없다. 기동을 막으면
     * 필요도 없는 설정을 넣으라고 강요하게 된다.
     */
    private void requirePublisherConfirms() {
        // CORRELATED 여야 한다. SIMPLE 은 CorrelationData 의 future 를 안 채워준다 —
        // 채널 단위로 기다리는 방식이라 "이 메시지" 를 지목할 수가 없어서다.
        boolean correlated =
                rabbitTemplate.getConnectionFactory() instanceof CachingConnectionFactory caching
                        && caching.isPublisherConfirms();
        if (correlated) {
            return;
        }
        throw new IllegalStateException(
                "publisher confirm 이 꺼져 있어서 제안을 보낼 수 없다. "
                        + "이 서비스의 application.yml 에 spring.rabbitmq.publisher-confirm-type=correlated 를 넣어야 한다. "
                        + "안 넣으면 확인 응답을 영영 못 받아서 제안마다 " + CONFIRM_TIMEOUT.toSeconds() + "초씩 버리고 실패한다.");
    }

    private boolean confirmed(CorrelationData confirm, long orderId, long riderId) {
        try {
            CorrelationData.Confirm result =
                    confirm.getFuture().get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (result != null && result.isAck()) {
                return true;
            }
            log.error("제안 발행을 브로커가 거절했다: orderId={} riderId={} 이유={}",
                    orderId, riderId, result == null ? "응답 없음" : result.getReason());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.error("제안 발행 확인 실패: orderId={} riderId={}", orderId, riderId, e);
            return false;
        }
    }
}
