package com.delivery.common.dispatch;

import com.delivery.common.Ids;
import com.delivery.common.RabbitTopology;
import com.delivery.common.Times;
import com.delivery.common.event.DispatchOffer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;

/**
 * DE-03 제안 발송. 후보 목록에서 하나씩 꺼내 라이더를 찜하고 래빗엠큐로 제안을 던진다.
 *
 * <p>dispatch-engine 의 첫 제안과 offer-relay 의 재제안이 <b>글자 하나까지 같은 일</b>이라
 * libs/common 에 둔다. 다른 점은 시작 attempt 번호뿐이다.
 */
@RequiredArgsConstructor
public class OfferSender {

    private static final Logger log = LoggerFactory.getLogger(OfferSender.class);

    private final ObjectProvider<OfferChannel> channelProvider;
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
        // 재제안할 때마다 새 offerId 를 만든다. 펜싱 규칙(기능 정의서 3.9)이 이걸로 판정한다 —
        // 옛 타이머 메시지가 뒤늦게 도착해도 offerId 가 달라서 버려진다.
        long offerId = Ids.newId();
        Instant offeredAt = Times.now();

        offerBoard.writeOffered(orderId, offerId, riderId, attempt, offeredAt.toEpochMilli());
        riderState.markOffered(riderId, offerId);

        DispatchOffer offer = new DispatchOffer(offerId, orderId, riderId, attempt, offeredAt);

        if (!channel().send(offer)) {
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
     * 2단계 실험: 래빗엠큐인지 카프카인지는 delivery.offer.transport 로 고른다.
     *
     * <p>기동할 때 안 받고 쓸 때 꺼내는 건 notification-worker 때문이다. 걔도 레디스를 써서
     * 이 빈이 같이 만들어지는데 제안을 보낼 일은 없다.
     */
    private OfferChannel channel() {
        OfferChannel channel = channelProvider.getIfAvailable();
        if (channel == null) {
            throw new IllegalStateException("OfferChannel 이 없다. 래빗엠큐나 카프카 중 하나는 있어야 제안을 보낸다");
        }
        return channel;
    }
}
