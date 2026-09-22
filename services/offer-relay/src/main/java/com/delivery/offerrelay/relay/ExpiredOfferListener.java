package com.delivery.offerrelay.relay;

import com.delivery.common.RabbitTopology;
import com.delivery.common.event.DispatchOffer;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * RE-02 의 입구. {@code dispatch.offer.expired} 를 받는다.
 *
 * <p>여기 들어오는 메시지는 두 갈래다. 하나는 타이머 큐에서 10초 TTL 이 지나 DLX 로 떨어진 것,
 * 하나는 DE-05 가 라이더 거절을 받고 곧바로 DLX 에 넣은 것이다. 둘을 갈라서 처리하지 않는다 —
 * "이 제안은 끝났으니 다음 사람에게 넘겨라" 로 할 일이 같다.
 *
 * <p><b>왜 여기는 수동 ack 가 아닌가.</b> 카프카 컨슈머는 전부 수동 ack 인데 여기만 다르다.
 * 이름이 비슷해서 같은 것 같지만 하는 일이 다르다.
 *
 * <p>카프카의 자동 커밋은 <b>시간을 보고</b> 오프셋을 옮긴다. 5초마다 "여기까지 읽었다" 를
 * 적는데, 내가 그 메시지를 처리했는지는 안 본다. 그래서 처리 도중에 죽으면 그 주문이 통째로
 * 사라진다. 그래서 수동이어야 한다.
 *
 * <p>스프링 AMQP 의 AUTO 는 <b>리스너가 예외 없이 끝났는지를 보고</b> ack 한다. 던지면 ack 를
 * 안 하고 되돌린다. 이름만 자동이지 하는 일은 우리가 손으로 짤 ack 와 같다. 게다가 손으로
 * ack 하려고 예외를 잡아버리면 스프링의 재시도(3회 백오프)가 아예 안 걸린다. 그러면 레디스가
 * 잠깐 끊긴 동안 메시지가 곧바로 큐로 돌아가 초당 수천 번 다시 도는 뜨거운 루프가 된다.
 *
 * <p>그래서 예외를 잡지 않고 그대로 올린다. 3회 백오프로도 안 되면 스프링이 requeue 없이
 * 버리는데, 그 큐에 DLX 를 걸어놔서 {@code dispatch.offer.expired.dlq} 로 간다.
 * 그냥 버리면 그 주문은 재제안을 영영 못 받고 손님 화면에 "배차 중" 이 계속 떠 있는다.
 */
@Component
@RequiredArgsConstructor
public class ExpiredOfferListener {

    private final OfferRelayService relayService;

    @RabbitListener(queues = RabbitTopology.Q_OFFER_EXPIRED)
    public void onExpired(DispatchOffer offer) {
        relayService.relay(offer);
    }
}
