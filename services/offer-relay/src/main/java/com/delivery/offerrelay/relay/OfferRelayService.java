package com.delivery.offerrelay.relay;

import com.delivery.common.Times;
import com.delivery.common.dispatch.DispatchEventPublisher;
import com.delivery.common.dispatch.DispatchLease;
import com.delivery.common.dispatch.ExpiryDecision;
import com.delivery.common.dispatch.OfferBoard;
import com.delivery.common.dispatch.OfferSender;
import com.delivery.common.dispatch.OfferState;
import com.delivery.common.dispatch.RiderState;
import com.delivery.common.event.DispatchOffer;
import com.delivery.offerrelay.config.RelayProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * RE-02 만료 제안 처리. 기능 정의서의 8단계를 그대로 밟는다.
 *
 * <p>이 서비스가 하는 일을 한 줄로 쓰면 "1순위가 안 받았으니 2순위에게 넘겨라" 다.
 * 그게 전부인데, 그 한 줄을 안전하게 만드는 데 장치가 세 개 붙는다.
 *
 * <pre>
 * 1) 배차 리스   — dispatch-engine 의 좀비 재배차와 겹치지 않게
 * 2) 만료 Lua    — 라이더의 수락과 겹치지 않게
 * 3) 펜싱 규칙   — 이미 다음 후보로 넘어간 뒤 도착한 옛 메시지를 버리게
 * </pre>
 *
 * <p>왜 셋이나 필요한지는 각각 <b>막는 상대가 다르기</b> 때문이다. 리스는 다른 인스턴스를 막고,
 * Lua 는 HTTP 로 들어오는 수락을 막고(수락은 리스를 안 잡으니 리스로는 못 막는다),
 * 펜싱은 시간을 거슬러 도착한 메시지를 막는다. 하나라도 빼면 그 자리로 사고가 들어온다.
 */
@Service
@RequiredArgsConstructor
public class OfferRelayService {

    private static final Logger log = LoggerFactory.getLogger(OfferRelayService.class);

    private final DispatchLease dispatchLease;
    private final OfferBoard offerBoard;
    private final OfferSender offerSender;
    private final RiderState riderState;
    private final DispatchEventPublisher eventPublisher;
    private final RelayProperties properties;

    @Value("${spring.application.name}:${server.port}")
    private String instanceId;

    public void relay(DispatchOffer expired) {
        long orderId = expired.orderId();
        long now = Times.now().toEpochMilli();
        String owner = instanceId + ":" + now;

        if (dispatchLease.acquire(orderId, owner) == null) {
            // dispatch-engine 이 좀비 판정으로 이 주문을 다시 배차하고 있거나, 다른 relay
            // 인스턴스가 같은 메시지를 처리 중이다. 어느 쪽이든 그쪽이 다음 후보를 잡는다.
            // 여기서 같이 진행하면 후보를 두 명 꺼내서 제안이 두 개 나간다.
            log.debug("배차 리스를 못 잡았다. 다른 쪽이 처리 중이다: orderId={}", orderId);
            return;
        }

        try {
            handle(expired, now);
        } finally {
            if (!dispatchLease.release(orderId, owner)) {
                log.warn("리스를 잃은 채로 재제안을 진행했다: orderId={} owner={}", orderId, owner);
            }
        }
    }

    private void handle(DispatchOffer expired, long now) {
        long orderId = expired.orderId();

        // 1~3단계. 펜싱, 수락 여부, 취소 여부를 Lua 한 번에 본다.
        ExpiryDecision decision = offerBoard.expire(orderId, expired.offerId(), now);
        if (!decision.shouldRetry()) {
            logSkip(decision, expired);
            return;
        }

        // 5단계. 직전 라이더를 놓아준다.
        //
        // 순서가 중요하다. 다음 후보를 찾기 전에 풀어야 한다. 뒤로 미루면 이 라이더가
        // 자기 자신의 다음 후보가 될 수 없다 — 후보 목록에 남아 있는데 찜이 걸려 있어서
        // 건너뛰어진다. 라이더가 두 명뿐인 존에서는 이게 곧바로 배차 실패가 된다.
        //
        // false 는 이상한 게 아니다. DE-05 로 거절한 경우 이미 풀려 있고, 그 사이 다른 주문이
        // 이 라이더를 가져갔을 수도 있다. 둘 다 그대로 두는 게 맞아서 로그만 남긴다.
        if (!riderState.release(expired.riderId(), orderId, expired.offerId())) {
            log.debug("라이더 {} 는 이미 놓여났거나 다른 주문이 가져갔다", expired.riderId());
        }

        // 4·6단계. 다섯 번을 다 썼으면 여기서 끝낸다.
        int nextAttempt = expired.attempt() + 1;
        if (nextAttempt > properties.maxAttempts()) {
            fail(orderId, nextAttempt - 1, "MAX_ATTEMPTS");
            return;
        }

        // 6~8단계. 다음 후보를 꺼내 새 offerId 로 다시 던진다.
        // (새 offerId 를 발급하는 건 OfferSender 안에서 한다. 그래야 펜싱이 걸린다)
        DispatchOffer next = offerSender.offerToNextCandidate(orderId, nextAttempt);
        if (next == null) {
            fail(orderId, expired.attempt(), "NO_CANDIDATE_LEFT");
            return;
        }

        log.info("재제안: orderId={} {}번 → {}번 라이더, attempt {}→{}",
                orderId, expired.riderId(), next.riderId(), expired.attempt(), nextAttempt);
    }

    private void fail(long orderId, int attempt, String reason) {
        offerBoard.writeState(orderId, OfferState.FAILED);
        eventPublisher.publishFailed(orderId, reason);
        log.info("배차 실패: orderId={} attempt={} 이유={}", orderId, attempt, reason);
    }

    /**
     * 진행하지 않은 이유를 남긴다.
     *
     * <p>넷을 갈라서 찍는 게 나중에 값을 한다. 만료 큐가 이상하게 두꺼워졌을 때 STALE 이 많으면
     * 라이더들이 빨리빨리 거절하고 있다는 뜻이라 정상이고, GONE 이 많으면 재제안이 10분 넘게
     * 늦고 있다는 뜻이라 사고다. 로그가 "처리 안 함" 한 줄이면 이 둘을 못 가른다.
     */
    private void logSkip(ExpiryDecision decision, DispatchOffer expired) {
        switch (decision) {
            case STALE -> log.debug("펜싱에 걸렸다. 이미 다음 후보로 넘어간 뒤 온 메시지다: "
                    + "orderId={} offerId={}", expired.orderId(), expired.offerId());
            case ACCEPTED -> log.debug("라이더가 이미 수락했다: orderId={} riderId={}",
                    expired.orderId(), expired.riderId());
            case CLOSED -> log.debug("이미 끝난 주문이다: orderId={}", expired.orderId());
            case GONE -> log.warn("제안 보드가 없다. TTL 10분이 지났을 만큼 늦게 도착했다: "
                    + "orderId={} offerId={}", expired.orderId(), expired.offerId());
            case RETRY -> throw new IllegalStateException("여기 올 수 없다");
        }
    }
}
