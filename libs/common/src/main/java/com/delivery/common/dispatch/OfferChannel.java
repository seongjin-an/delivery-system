package com.delivery.common.dispatch;

import com.delivery.common.event.DispatchOffer;

/**
 * 제안을 브로커로 내보내는 자리. 2단계 실험에서 래빗엠큐와 카프카를 갈아끼우려고 뽑았다.
 *
 * <p>하는 일은 둘이다. 제안을 알림과 타이머 양쪽으로 보내는 것, 거절된 제안을 타이머를 건너뛰고
 * 곧바로 만료 쪽으로 보내는 것.
 */
public interface OfferChannel {

    /** 브로커가 받았다고 답했으면 true. 못 받았으면 OfferSender 가 찜을 되돌리고 다음 후보로 간다 */
    boolean send(DispatchOffer offer);

    /** DE-05 거절. 10초를 안 기다리고 바로 재제안하게 만료 쪽으로 넣는다 */
    void expireNow(DispatchOffer offer);
}
