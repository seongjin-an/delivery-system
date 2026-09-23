package com.delivery.dispatchengine;

import com.delivery.common.Times;
import com.delivery.common.dispatch.CandidateList;
import com.delivery.common.dispatch.DispatchEventPublisher;
import com.delivery.common.dispatch.DispatchLease;
import com.delivery.common.dispatch.OfferBoard;
import com.delivery.common.dispatch.OfferSender;
import com.delivery.common.dispatch.OfferSnapshot;
import com.delivery.common.dispatch.OfferState;
import com.delivery.common.event.DispatchOffer;
import com.delivery.common.event.OrderCreated;
import com.delivery.dispatchengine.candidate.Candidate;
import com.delivery.dispatchengine.candidate.CandidateFinder;
import com.delivery.dispatchengine.config.DispatchProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * DE-01 배차 시작. {@code order.created} 하나를 받아 제안 하나를 던지기까지.
 *
 * <p>순서는 리스 획득 → 좀비 판정 → 후보 검색 → 후보 저장 → 제안 발송 → 리스 해제다.
 * 앞의 두 개가 "같은 주문을 두 번 배차하지 않기" 를 담당하는데, 서로 막는 게 다르다.
 * 리스는 <b>지금 동시에</b> 처리하는 걸 막고, 제안 보드는 <b>전에 처리한 적 있는지</b>를 본다.
 * 리스는 15초 뒤 사라져서 20초 뒤에 온 중복 메시지를 못 막기 때문에 둘 다 필요하다.
 */
@Service
@RequiredArgsConstructor
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);

    private final DispatchLease dispatchLease;
    private final OfferBoard offerBoard;
    private final CandidateList candidateList;
    private final CandidateFinder candidateFinder;
    private final OfferSender offerSender;
    private final DispatchEventPublisher eventPublisher;
    private final DispatchProperties properties;

    @Value("${spring.application.name}:${server.port}")
    private String instanceId;

    public void dispatch(OrderCreated order) {
        long orderId = order.orderId();
        long now = Times.now().toEpochMilli();
        String owner = instanceId + ":" + now;

        if (dispatchLease.acquire(orderId, owner) == null) {
            // 다른 인스턴스가 지금 이 주문을 잡고 있다. 그쪽이 끝낼 테니 우리는 그냥 넘어간다.
            log.debug("배차 리스를 못 잡았다. 다른 인스턴스가 처리 중이다: orderId={}", orderId);
            return;
        }

        try {
            if (!shouldProceed(orderId, now)) {
                return;
            }

            List<Candidate> candidates = candidateFinder.find(order.storeLat(), order.storeLng(), now);
            if (candidates.isEmpty()) {
                log.info("반경 {}m 안에 한가한 라이더가 없다: orderId={} zone={}",
                        properties.searchRadiusMeters(), orderId, order.zoneId());
                offerBoard.writeState(orderId, OfferState.FAILED);
                eventPublisher.publishFailed(orderId, "NO_CANDIDATE");
                return;
            }

            candidateList.replace(orderId, candidates.stream().map(Candidate::riderId).toList());

            DispatchOffer offer = offerSender.offerToNextCandidate(orderId, 1);
            if (offer == null) {
                // 후보는 있었는데 전부 다른 주문에 찜당했거나 발행이 실패했다.
                log.info("후보 {}명을 다 시도했는데 제안을 못 보냈다: orderId={}", candidates.size(), orderId);
                offerBoard.writeState(orderId, OfferState.FAILED);
                eventPublisher.publishFailed(orderId, "ALL_CANDIDATES_TAKEN");
                return;
            }

            eventPublisher.publishDispatching(orderId);
        } finally {
            if (!dispatchLease.release(orderId, owner)) {
                // TTL 15초가 지나 남이 리스를 가져간 상태에서 우리가 일을 하고 있었다는 뜻이다.
                // 같은 주문에 제안이 두 개 나갔을 수 있으니 반드시 눈에 띄어야 한다.
                log.warn("리스를 잃은 채로 배차를 진행했다: orderId={} owner={}", orderId, owner);
            }
        }
    }

    /**
     * 좀비 판정. 기능 정의서 DE-01 규칙 2번의 표 그대로다.
     *
     * <p>마지막 줄이 핵심이다. 제안 유효시간이 10초인데 30초가 지나도 OFFERED 라면 타이머
     * 메시지가 애초에 없었다는 뜻이다. 정상이면 10초에 만료돼서 다른 상태로 바뀌어 있다.
     * {@code EXISTS} 만 보고 넘기면 그런 주문이 영영 방치된다.
     */
    private boolean shouldProceed(long orderId, long now) {
        OfferSnapshot snapshot = offerBoard.read(orderId);
        if (snapshot == null) {
            return true;                                  // 처음 보는 주문
        }
        return switch (snapshot.state()) {
            case ACCEPTED -> {
                log.debug("이미 배차된 주문이다: orderId={}", orderId);
                yield false;
            }
            case CANCELLED -> {
                log.debug("취소된 주문이다: orderId={}", orderId);
                yield false;
            }
            case EXPIRED, FAILED, REJECTED -> true;       // 끝났지만 다시 해도 된다
            case OFFERED -> {
                long elapsed = now - snapshot.offeredAt();
                if (elapsed <= properties.zombieThreshold().toMillis()) {
                    log.debug("제안이 진행 중이다. offer-relay 가 이어받는다: orderId={}", orderId);
                    yield false;
                }
                log.warn("좀비 주문이다. {}ms 째 OFFERED 인데 타이머가 없다: orderId={} offerId={}",
                        elapsed, orderId, snapshot.offerId());
                yield true;
            }
        };
    }
}
