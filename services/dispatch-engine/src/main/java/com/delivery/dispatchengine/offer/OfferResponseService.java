package com.delivery.dispatchengine.offer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.delivery.common.dispatch.ExperimentTimers;
import com.delivery.common.RabbitTopology;
import com.delivery.common.Times;
import com.delivery.common.dispatch.DispatchEventPublisher;
import com.delivery.common.dispatch.OfferBoard;
import com.delivery.common.dispatch.OfferDecision;
import com.delivery.common.dispatch.OfferSnapshot;
import com.delivery.common.dispatch.OfferState;
import com.delivery.common.dispatch.RiderLock;
import com.delivery.common.dispatch.RiderState;
import com.delivery.common.event.DispatchOffer;
import com.delivery.common.exception.BusinessException;
import com.delivery.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * DE-04 제안 수락, DE-05 제안 거절. 라이더가 제안에 답하는 두 가지 길.
 *
 * <p>둘 다 순서가 같다. orderId 되찾기 → Lua 판정 → 라이더 상태 바꾸기 → 알리기.
 * Lua 가 확정을 돌려준 뒤에만 나머지가 돈다. 그래야 만료와 부딪혔을 때 진 쪽이 아무것도 안 건드린다.
 *
 * <p>한 클래스에 둔 이유는 앞의 두 단계가 글자 하나까지 같아서다. 나눠두면 "offerId 를
 * riderId 보다 먼저 본다" 같은 규칙을 한쪽에만 고치는 날이 온다.
 */
@Service
@RequiredArgsConstructor
public class OfferResponseService {

    private static final Logger log = LoggerFactory.getLogger(OfferResponseService.class);

    private Timer acceptTimer;
    private com.delivery.common.dispatch.MysqlDispatchOutbox outbox;
    private org.springframework.transaction.support.TransactionTemplate tx;
    private Timer rejectTimer;

    /** 2단계 실험: 배차 상태 저장소(레디스 / MySQL)를 같은 자리에서 잰다. 테스트에선 안 불려서 타이머 없이 돈다 */
    @org.springframework.beans.factory.annotation.Autowired
    void experimentTimers(MeterRegistry registry,
                          org.springframework.beans.factory.ObjectProvider<com.delivery.common.dispatch.MysqlDispatchOutbox> outbox,
                          org.springframework.beans.factory.ObjectProvider<org.springframework.transaction.support.TransactionTemplate> tx) {
        // 2단계 실험: 배차 상태가 MySQL 이면 수락과 배차 확정 이벤트를 한 트랜잭션에 묶는다(MysqlDispatchOutbox 주석)
        this.outbox = outbox.getIfAvailable();
        this.tx = this.outbox == null ? null : tx.getIfAvailable();
        this.acceptTimer = ExperimentTimers.slo(registry, "offer_accept_duration");
        this.rejectTimer = ExperimentTimers.slo(registry, "offer_reject_duration");
    }

    private final OfferBoard offerBoard;
    private final RiderState riderState;
    private final RiderLock riderLock;
    private final DispatchEventPublisher eventPublisher;
    private final RabbitTemplate rabbitTemplate;

    /**
     * DE-04 수락. 여기를 통과하면 배차가 확정된다.
     *
     * <p><b>{@code lock:rider} 는 일부러 안 푼다.</b> 배달 완료(OR-04)까지 이 라이더에게
     * 다른 주문이 붙으면 안 되기 때문이다. 찜은 12초짜리라 곧 저절로 풀리는데, 그때부터는
     * {@code status = DELIVERING} 이 대신 지킨다 — 후보 검색이 IDLE 만 뽑아서다.
     *
     * <p>그래서 순서가 중요하다. 상태 갱신이 실패한 채로 12초가 지나면 라이더는 IDLE 인데
     * 찜도 풀려서, 배달 중인 사람에게 새 제안이 간다.
     */
    public Assignment accept(long offerId, long riderId) {
        return acceptTimer == null ? acceptMeasured(offerId, riderId) : acceptTimer.record(() -> acceptMeasured(offerId, riderId));
    }

    private Assignment acceptMeasured(long offerId, long riderId) {
        boolean viaOutbox = outbox != null && tx != null;
        Settled settled = viaOutbox
                ? tx.execute(status -> {
                    Settled s = settle(offerId, riderId, OfferState.ACCEPTED);
                    outbox.recordAssigned(s.orderId(), riderId, offerId, s.attempt());
                    return s;
                })
                : settle(offerId, riderId, OfferState.ACCEPTED);

        riderState.markDelivering(riderId, settled.orderId());

        // 수락 Lua 와 markDelivering 사이에 손님이 취소하면(OR-05) 취소 쪽은 라이더가 아직 DELIVERING 이 아니라서
        // 못 풀어준다. 그대로 두면 취소된 주문 때문에 라이더가 영영 DELIVERING 으로 남는다.
        // 그래서 여기서 한 번 더 본다. 취소가 먼저였으면 우리가 풀고, 우리가 먼저였으면 취소 쪽이 푼다.
        OfferSnapshot after = offerBoard.read(settled.orderId());
        if (after != null && after.state() == OfferState.CANCELLED) {
            riderState.finishDelivery(riderId, settled.orderId());
            riderLock.release(riderId, settled.orderId());
            log.info("수락하는 사이 주문이 취소됐다: orderId={} riderId={} offerId={}", settled.orderId(), riderId, offerId);
            throw new BusinessException(ErrorCode.OFFER_EXPIRED, "주문이 취소됐어요");
        }

        if (!viaOutbox) {
            eventPublisher.publishAssigned(settled.orderId(), riderId, offerId, settled.attempt());
        }

        log.info("배차 확정: orderId={} riderId={} offerId={} attempt={}",
                settled.orderId(), riderId, offerId, settled.attempt());
        return new Assignment(settled.orderId(), riderId, offerId, settled.attempt());
    }

    /**
     * DE-05 거절. 10초를 기다릴 이유가 없으니 곧바로 다음 후보로 넘긴다.
     *
     * <p>재제안을 여기서 직접 하지 않고 {@code dispatch.dlx} 에 만료 메시지를 넣는다.
     * 그러면 offer-relay 의 RE-02 가 평소처럼 받아서 처리한다. 재제안 절차(후보 꺼내기,
     * 찜하기, attempt 올리기, 소진되면 실패 처리)를 두 군데에 두지 않으려는 것이다.
     *
     * <p>원래 걸려 있던 10초 타이머는 그대로 흐르다가 나중에 도착하는데, 그때는 보드의
     * {@code offerId} 가 이미 바뀌어 있어서 펜싱 규칙(기능 정의서 3.9)에 걸려 버려진다.
     */
    public Rejection reject(long offerId, long riderId) {
        return rejectTimer == null ? rejectMeasured(offerId, riderId) : rejectTimer.record(() -> rejectMeasured(offerId, riderId));
    }

    private Rejection rejectMeasured(long offerId, long riderId) {
        Settled settled = settle(offerId, riderId, OfferState.REJECTED);

        riderState.release(riderId, settled.orderId(), offerId);
        riderState.countReject(riderId);
        handOverToRelay(settled, offerId, riderId);

        log.info("제안 거절: orderId={} riderId={} offerId={} attempt={}",
                settled.orderId(), riderId, offerId, settled.attempt());
        return new Rejection(settled.orderId(), riderId, offerId, settled.attempt());
    }

    /** 수락과 거절이 공통으로 밟는 두 단계. 실패하면 여기서 바로 예외로 끝난다 */
    private Settled settle(long offerId, long riderId, OfferState target) {
        Long orderId = offerBoard.findOrderId(offerId);
        if (orderId == null) {
            // 인덱스가 TTL 10분을 넘겼거나 아예 없던 offerId 다. 어느 쪽이든 라이더에게 할 말은 같다.
            log.info("모르는 제안에 응답하려 한다: offerId={} riderId={} target={}",
                    offerId, riderId, target);
            throw new BusinessException(OfferDecision.EXPIRED.errorCode());
        }

        OfferDecision decision =
                offerBoard.respond(orderId, offerId, riderId, target, Times.now().toEpochMilli());
        if (!decision.isApplied()) {
            log.info("응답 거부: offerId={} orderId={} riderId={} target={} 이유={}",
                    offerId, orderId, riderId, target, decision);
            throw new BusinessException(decision.errorCode());
        }

        // 여기부터는 이 요청이 판정에서 이긴 게 확정이다. 진 쪽은 이제 보드를 보고 물러난다.
        OfferSnapshot snapshot = offerBoard.read(orderId);
        return new Settled(orderId, snapshot == null ? 0 : snapshot.attempt());
    }

    /**
     * 만료 큐로 곧장 넣는다. 타이머 큐(TTL 10초)가 아니라 DLX 로 바로 보내는 게 요점이다 —
     * 라이더가 이미 거절했는데 10초를 또 세고 있을 이유가 없다.
     *
     * <p>여기가 실패하면 재제안이 안 돈다. 다만 원래 타이머가 10초 뒤에 도착하니까 그때
     * 이어받는다. 늦어질 뿐 주문이 사라지지는 않아서 예외를 올리지 않고 경고만 남긴다 —
     * 예외를 올리면 라이더에게 500이 가는데, 라이더 입장에서 거절은 이미 처리된 뒤다.
     */
    private void handOverToRelay(Settled settled, long offerId, long riderId) {
        DispatchOffer expired = new DispatchOffer(
                offerId, settled.orderId(), riderId, settled.attempt(), Times.now());
        try {
            rabbitTemplate.convertAndSend(
                    RabbitTopology.DISPATCH_DLX, RabbitTopology.RK_OFFER_EXPIRED, expired);
        } catch (Exception e) {
            log.warn("거절 뒤 재제안 요청을 못 넣었다. 10초 뒤 원래 타이머가 이어받는다: "
                    + "orderId={} offerId={}", settled.orderId(), offerId, e);
        }
    }

    private record Settled(long orderId, int attempt) {
    }

    public record Assignment(long orderId, long riderId, long offerId, int attempt) {
    }

    public record Rejection(long orderId, long riderId, long offerId, int attempt) {
    }
}
