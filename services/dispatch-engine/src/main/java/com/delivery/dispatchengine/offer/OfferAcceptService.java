package com.delivery.dispatchengine.offer;

import com.delivery.common.RedisKeys;
import com.delivery.common.Times;
import com.delivery.common.exception.BusinessException;
import com.delivery.common.rider.RiderStateFields;
import com.delivery.common.rider.RiderStatus;
import com.delivery.dispatchengine.kafka.DispatchEventPublisher;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * DE-04 제안 수락.
 *
 * <p>순서는 orderId 되찾기 → Lua 판정 → 라이더 상태 바꾸기 → 카프카 발행이다.
 * Lua 가 1을 돌려준 뒤에만 나머지가 돈다. 그래야 만료와 부딪혔을 때 진 쪽이 아무것도 안 건드린다.
 */
@Service
@RequiredArgsConstructor
public class OfferAcceptService {

    private static final Logger log = LoggerFactory.getLogger(OfferAcceptService.class);

    private final OfferBoard offerBoard;
    private final DispatchEventPublisher eventPublisher;
    private final StringRedisTemplate redis;

    public Assignment accept(long offerId, long riderId) {
        Long orderId = offerBoard.findOrderId(offerId);
        if (orderId == null) {
            // 인덱스가 TTL 10분을 넘겼거나 아예 없던 offerId 다. 어느 쪽이든 라이더에게 할 말은 같다.
            log.info("모르는 제안을 수락하려 한다: offerId={} riderId={}", offerId, riderId);
            throw new BusinessException(AcceptResult.EXPIRED.errorCode());
        }

        AcceptResult result = offerBoard.accept(orderId, offerId, riderId, Times.now().toEpochMilli());
        if (!result.isAccepted()) {
            log.info("수락 거부: offerId={} orderId={} riderId={} 이유={}",
                    offerId, orderId, riderId, result);
            throw new BusinessException(result.errorCode());
        }

        // 여기부터는 이 요청이 판정에서 이긴 게 확정이다. 만료 쪽은 이제 보드에서 ACCEPTED 를 보고 버린다.
        markDelivering(riderId, orderId);

        OfferSnapshot snapshot = offerBoard.read(orderId);
        int attempt = snapshot == null ? 0 : snapshot.attempt();

        eventPublisher.publishAssigned(orderId, riderId, offerId, attempt);
        log.info("배차 확정: orderId={} riderId={} offerId={} attempt={}",
                orderId, riderId, offerId, attempt);

        return new Assignment(orderId, riderId, offerId, attempt);
    }

    /**
     * 라이더를 배달 중으로 바꾼다.
     *
     * <p><b>{@code lock:rider} 는 일부러 안 푼다.</b> 배달 완료(OR-04)까지 이 라이더에게
     * 다른 주문이 붙으면 안 되기 때문이다. 찜은 12초짜리라 곧 저절로 풀리는데, 그때부터는
     * {@code status = DELIVERING} 이 대신 지킨다 — 후보 검색이 IDLE 만 뽑아서다.
     *
     * <p>그래서 순서가 중요하다. 이 HSET 이 실패한 채로 12초가 지나면 라이더는 IDLE 인데
     * 찜도 풀려서, 배달 중인 사람에게 새 제안이 간다.
     */
    private void markDelivering(long riderId, long orderId) {
        redis.opsForHash().putAll(RedisKeys.riderState(riderId), Map.of(
                RiderStateFields.STATUS, RiderStatus.DELIVERING.name(),
                RiderStateFields.CURRENT_ORDER_ID, Long.toString(orderId)));
    }

    public record Assignment(long orderId, long riderId, long offerId, int attempt) {
    }
}
