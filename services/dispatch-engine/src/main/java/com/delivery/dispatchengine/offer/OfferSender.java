package com.delivery.dispatchengine.offer;

import com.delivery.common.Ids;
import com.delivery.common.RabbitTopology;
import com.delivery.common.RedisKeys;
import com.delivery.common.Times;
import com.delivery.common.event.DispatchOffer;
import com.delivery.common.rider.RiderStateFields;
import com.delivery.common.rider.RiderStatus;
import com.delivery.dispatchengine.config.DispatchProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * DE-03 제안 발송. 후보 목록에서 하나씩 꺼내 라이더를 찜하고 래빗엠큐로 제안을 던진다.
 */
@Component
@RequiredArgsConstructor
public class OfferSender {

    private static final Logger log = LoggerFactory.getLogger(OfferSender.class);

    /** publisher confirm 을 기다리는 한도 */
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbitTemplate;
    private final OfferBoard offerBoard;
    private final DispatchProperties properties;

    /**
     * 후보를 하나씩 꺼내면서 찜에 성공한 첫 라이더에게 제안을 보낸다.
     *
     * @return 보냈으면 그 제안, 후보가 다 떨어졌으면 null
     */
    public DispatchOffer offerToNextCandidate(long orderId, int startAttempt) {
        int attempt = startAttempt;
        String riderIdText;

        while ((riderIdText = redis.opsForList().leftPop(RedisKeys.candidates(orderId))) != null) {
            long riderId = Long.parseLong(riderIdText);

            // 라이더 찜. 값이 인스턴스가 아니라 주문 아이디인 게 배차 리스와 다른 점이다.
            // 주인이 프로세스가 아니라 주문이라서, offer-relay 가 재제안할 때 이 락을 풀 수 있다.
            Boolean claimed = redis.opsForValue().setIfAbsent(
                    RedisKeys.riderLock(riderId), Long.toString(orderId), properties.riderLockTtl());
            if (!Boolean.TRUE.equals(claimed)) {
                // 다른 주문이 방금 이 라이더를 채갔다. 다음 후보로 간다.
                log.debug("라이더 {} 는 이미 찜돼 있다. 다음 후보로", riderId);
                continue;
            }

            DispatchOffer offer = publish(orderId, riderId, attempt);
            if (offer != null) {
                return offer;
            }
            // 발행이 실패했으면 찜을 풀어주고 다음 후보로 넘어간다.
            releaseRider(orderId, riderId);
            attempt++;
        }
        return null;
    }

    private DispatchOffer publish(long orderId, long riderId, int attempt) {
        // 재제안할 때마다 새 offerId 를 만든다. 펜싱 규칙(기능 정의서 3.9)이 이걸로 판정한다 —
        // 옛 타이머 메시지가 뒤늦게 도착해도 offerId 가 달라서 버려진다.
        long offerId = Ids.newId();
        Instant offeredAt = Times.now();

        offerBoard.writeOffered(orderId, offerId, riderId, attempt, offeredAt.toEpochMilli());
        redis.opsForHash().put(
                RedisKeys.riderState(riderId), RiderStateFields.STATUS, RiderStatus.OFFERED.name());

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
            redis.opsForHash().put(
                    RedisKeys.riderState(riderId), RiderStateFields.STATUS, RiderStatus.IDLE.name());
            return null;
        }

        log.info("제안 발송: orderId={} riderId={} offerId={} attempt={}",
                orderId, riderId, offerId, attempt);
        return offer;
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

    private void releaseRider(long orderId, long riderId) {
        String key = RedisKeys.riderLock(riderId);
        // 내가 찜한 것일 때만 푼다. 그 사이 다른 주문이 채갔을 수 있다.
        if (Long.toString(orderId).equals(redis.opsForValue().get(key))) {
            redis.delete(key);
        }
    }
}
