package com.delivery.ridersimulator.scenario;

import java.util.Map;

/**
 * SM-03 상태. 기능 정의서의 필드에 몇 개를 더했다.
 *
 * <p>pickedUp 과 delivered 가 있어야 "수락한 게 끝까지 갔나" 가 보인다. accepted 는 수락 버튼을 누르기로
 * 정한 수라서, 수락이 OFFER_EXPIRED 로 튕긴 것도 들어 있다. 그게 몇 건인지는 failures 의
 * {@code accept:OFFER_EXPIRED} 로 본다.
 *
 * @param locationsSent  보낸 좌표 누적. locationsSentPerSec 가 이상해 보이면 이걸 location-ingest 의
 *                       location_ingest_received_total 과 맞춰본다. 첫 판에서 초당 값이 91 로 나왔는데
 *                       ingest 는 33 을 받은 적이 있어서 넣었다 (그 뒤로는 재현이 안 됐다)
 * @param offersReceived 이게 ordersCreated 보다 훨씬 크면 재제안이 많다는 뜻이다. 배차 품질의 첫 신호다
 * @param unknownRider   이 판에 없는 라이더 앞으로 온 제안. 앞 판의 라이더에게 늦게 온 제안이면 정상이다
 * @param failures       실패한 호출을 이유별로. 예: {@code accept:OFFER_EXPIRED=12}
 */
public record ScenarioStatus(
        boolean running,
        long elapsedSec,
        int riders,
        long onlineRiders,
        long locationsSentPerSec,
        long locationsSent,
        long ordersCreated,
        long offersReceived,
        long accepted,
        long rejected,
        long ignored,
        long pickedUp,
        long delivered,
        long unknownRider,
        Map<String, Long> failures
) {

    static ScenarioStatus idle() {
        return new ScenarioStatus(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of());
    }
}
