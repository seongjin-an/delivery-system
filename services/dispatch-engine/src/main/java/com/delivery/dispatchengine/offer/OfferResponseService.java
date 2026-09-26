package com.delivery.dispatchengine.offer;

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

import java.util.List;

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

    private final OfferBoard offerBoard;
    private final RiderState riderState;
    private final RiderLock riderLock;
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
     *
     * <p><b>배차 확정 이벤트는 여기서 안 보낸다.</b> 수락 Lua 가 ACCEPTED 를 쓰면서 레디스 아웃박스에 같이 넣고,
     * DispatchOutboxRelay 가 따로 보낸다. 예전엔 여기서 카프카로 바로 보냈는데, ACCEPTED 를 쓰고 보내기 전에
     * 죽으면 주문이 DISPATCHING 으로 영영 남았다(2단계 실험, kill -9 세 번에 2, 1, 0건).
     * 덤으로 수락 응답이 카프카 ack 를 안 기다리게 됐다.
     */
    public Assignment accept(long offerId, long riderId) {
        Settled settled = settle(offerId, riderId, OfferState.ACCEPTED);

        riderState.markDelivering(riderId, settled.orderId());

        // 수락 Lua 와 markDelivering 사이에 손님이 취소하면(OR-05) 취소 쪽은 라이더가 아직 DELIVERING 이 아니라서
        // 못 풀어준다. 그대로 두면 취소된 주문 때문에 라이더가 영영 DELIVERING 으로 남는다.
        // 그래서 여기서 한 번 더 본다. 취소가 먼저였으면 우리가 풀고, 우리가 먼저였으면 취소 쪽이 푼다.
        OfferSnapshot after = offerBoard.read(settled.orderId());
        if (after != null && after.state() == OfferState.CANCELLED) {
            riderState.finishDelivery(riderId, settled.orderId());
            riderLock.release(riderId, settled.orderId());
            // 배차 확정 이벤트는 수락 Lua 가 이미 아웃박스에 넣었다. order-api 는 CANCELLED 주문에 온 배차 확정을
            // 무시한다(assign 이 CREATED, DISPATCHING 에서만 바꾼다). 그래서 거둬들이지 않는다.
            log.info("수락하는 사이 주문이 취소됐다: orderId={} riderId={} offerId={}", settled.orderId(), riderId, offerId);
            throw new BusinessException(ErrorCode.OFFER_EXPIRED, "주문이 취소됐어요");
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

        // attempt 를 판정 전에 읽는다. 수락이면 아웃박스 이벤트에 실어야 해서다. 판정 전에 읽어도 되는 건
        // Lua 가 offerId 를 확인하기 때문이다. 그 사이 다음 제안으로 넘어갔으면 Lua 가 지고, 이 값은 안 쓰인다.
        // offerId 가 같으면 같은 제안이라 attempt 도 같다.
        OfferSnapshot before = offerBoard.read(orderId);
        int attempt = before != null && before.offerId() == offerId ? before.attempt() : 0;

        List<String> events = target == OfferState.ACCEPTED
                ? DispatchEventPublisher.assignedEvents(orderId, riderId, offerId, attempt)
                : List.of();
        OfferDecision decision =
                offerBoard.respond(orderId, offerId, riderId, target, Times.now().toEpochMilli(), events);
        if (!decision.isApplied()) {
            log.info("응답 거부: offerId={} orderId={} riderId={} target={} 이유={}",
                    offerId, orderId, riderId, target, decision);
            throw new BusinessException(decision.errorCode());
        }

        // 여기부터는 이 요청이 판정에서 이긴 게 확정이다. 진 쪽은 이제 보드를 보고 물러난다.
        return new Settled(orderId, attempt);
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
