package com.delivery.dispatchengine.api;

import java.util.List;
import java.util.Map;

/**
 * DE-06 배차 상태 덤프.
 *
 * <p>레디스에 흩어진 키 네 개를 한 번에 보여준다. 프론트가 없는 프로젝트라 배차가 이상할 때
 * {@code redis-cli} 로 키를 하나씩 쳐보는 수밖에 없는데, 그때 키 이름을 기억해내는 게 일이다.
 * 여기 한 번 부르면 "지금 이 주문이 어디까지 갔나" 가 한 화면에 나온다.
 *
 * @param offer      {@code dispatch:offer:{orderId}} 해시 전체
 * @param candidates 아직 안 써본 후보들. 쓴 사람은 LPOP 으로 빠져나가서 여기 안 보인다
 * @param leaseOwner {@code lock:dispatch:{orderId}} 값. 채워져 있으면 지금 누가 배차 중이라는 뜻
 * @param riderState 제안이 나가 있는 라이더의 {@code rider:state} 해시. 제안이 없으면 비어 있다
 */
public record DispatchStatusResponse(
        long orderId,
        Map<String, String> offer,
        List<String> candidates,
        String leaseOwner,
        Map<String, String> riderState
) {
}
