package com.delivery.common.event;

import java.time.Instant;

/**
 * notify.push 큐에 들어가는 알림 한 건. NW-01(배차 제안)과 NW-03(마케팅)이 넣고 NW-02 가 꺼낸다.
 *
 * <p>둘을 한 모양으로 둔 이유는 한 큐에 섞여야 우선순위가 의미가 있어서다. 제안은 priority 9,
 * 마케팅은 1 로 들어간다.
 *
 * <p>남은 시간을 여기 적지 않고 offeredAt 을 들고 간다. 큐에서 몇 초를 기다릴지 넣는 순간엔 모른다.
 * 시나리오 C 처럼 4초 밀렸다가 나가면 라이더한테 남은 건 6초인데, 넣을 때 10초라고 적어두면
 * 앱은 10초를 세다가 6초째에 410 을 받는다.
 *
 * @param offeredAt 제안이 나간 시각. 마케팅이면 null
 * @param text      마케팅 문구. 제안이면 null
 */
public record PushMessage(
        Kind kind,
        long riderId,
        long offerId,
        long orderId,
        Instant offeredAt,
        String text
) {

    public enum Kind {
        OFFER,
        MARKETING
    }

    public static PushMessage offer(DispatchOffer offer) {
        return new PushMessage(Kind.OFFER, offer.riderId(), offer.offerId(), offer.orderId(), offer.offeredAt(), null);
    }

    public static PushMessage marketing(long riderId, String text) {
        return new PushMessage(Kind.MARKETING, riderId, 0, 0, null, text);
    }
}
