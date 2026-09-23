package com.delivery.orderapi.api;

import jakarta.validation.constraints.NotNull;

/**
 * OR-03 픽업, OR-04 완료 요청 본문.
 *
 * <p>long 이 아니라 Long 에 @NotNull 이다. long 이면 빼먹고 보냈을 때 0 이 들어가서
 * "남의 주문이에요(403)" 가 나가는데, 그러면 앱 개발자는 권한 문제를 한참 들여다보게 된다.
 * 400 으로 "riderId 가 없다" 고 바로 알려주는 게 맞다.
 */
public record RiderActionRequest(
        @NotNull(message = "라이더 아이디가 필요해요")
        Long riderId
) {
}
