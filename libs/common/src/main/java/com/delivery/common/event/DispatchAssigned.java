package com.delivery.common.event;

import java.time.Instant;

/**
 * dispatch.assigned 토픽 페이로드. 라이더가 제안을 수락해서 배차가 끝났다 (DE-04).
 *
 * <p>order-api 의 OR-07 이 이걸 받아 주문을 ASSIGNED 로 바꾸고 riderId 와 attempt 를 채운다.
 * 전에는 발행하는 쪽이 Map 으로 필드 이름을 적어 보내고 있었는데, 받는 쪽도 이름을 따로 적으면
 * 한쪽만 바꿨을 때 에러 없이 값이 0 으로 들어온다. 둘 다 이 레코드를 쓰게 했다.
 *
 * @param attempt 몇 번째 후보에서 잡혔는지. 1 이면 1순위가 바로 받은 것
 * @param at      수락한 시각. timeline 에 이 시각으로 적는다
 */
public record DispatchAssigned(
        long orderId,
        long riderId,
        long offerId,
        int attempt,
        Instant at
) {
}
