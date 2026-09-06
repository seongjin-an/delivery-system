package com.delivery.dispatchengine.api;

import jakarta.validation.constraints.Positive;

/**
 * DE-04 요청 본문.
 *
 * <p>riderId 를 왜 받냐면, 제안이 그 사람 것인지 봐야 해서다. URL 의 offerId 만으로 수락시키면
 * 남의 offerId 를 주워 온 라이더가 그 주문을 채갈 수 있다. 지금은 인증이 없어서 이 값이 곧
 * 신원인데, 실제 서비스라면 토큰에서 꺼내야 할 값이다.
 */
public record AcceptOfferRequest(

        @Positive(message = "라이더 아이디가 필요해요")
        long riderId
) {
}
