package com.delivery.common.event;

import java.time.Instant;

/**
 * dispatch.failed 토픽 페이로드. 배차를 포기했다.
 *
 * <p>두 군데서 나간다. dispatch-engine 은 반경 안에 한가한 라이더가 없거나(NO_CANDIDATE)
 * 후보가 전부 다른 주문에 찜당했을 때(ALL_CANDIDATES_TAKEN), offer-relay 는 다섯 번 제안했는데
 * 아무도 안 받았거나(MAX_ATTEMPTS) 다섯 번이 되기 전에 후보가 바닥났을 때(NO_CANDIDATE_LEFT) 보낸다.
 *
 * @param attempt 포기하기 전까지 라이더에게 실제로 간 제안 수. 후보가 아예 없었으면 0 이다.
 *                예전엔 이 필드가 없어서 다섯 번 제안하고 실패한 주문도 조회하면 attempt 0 으로 보였다.
 *                토픽에 남은 옛 이벤트에는 이 필드가 없는데, 그러면 0 으로 읽힌다(JsonUtil 이 모르는 필드와 빠진 필드를 봐준다)
 * @param reason  왜 포기했는지. 사람이 읽을 값이라 enum 으로 못 박지 않았다
 */
public record DispatchFailed(
        long orderId,
        int attempt,
        String reason,
        Instant at
) {
}
