package com.delivery.common.dispatch;

import com.delivery.common.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * {@code lock:dispatch:{orderId}} — 같은 주문을 두 번 배차하는 걸 막는다.
 *
 * <p>카프카는 at-least-once 라서 같은 {@code order.created} 가 두 번 올 수 있다. 컨슈머가 일을
 * 끝내고 오프셋을 커밋하기 직전에 죽으면, 리밸런싱 뒤에 다른 인스턴스가 그 메시지를 다시 받는다.
 * 둘 다 배차를 진행하면 주문 하나에 라이더 두 명이 붙는다.
 *
 * <p>값에 인스턴스 이름을 넣는 게 핵심이다. 풀 때 "이게 내가 잡은 락인가" 를 봐야 해서다.
 *
 * <p><b>이 락은 "지금 누가 처리 중인가" 만 알려준다. "전에 처리한 적 있나" 는 못 알려준다.</b>
 * 15초 뒤 사라지니까 중복 메시지가 20초 뒤에 오면 그냥 통과한다. 그래서 제안 보드
 * ({@code dispatch:offer})를 따로 봐야 한다. 회의실 문의 "사용 중" 표찰이 지금은 알려줘도
 * 어제 누가 썼는지는 안 알려주는 것과 같다.
 *
 * <p><b>offer-relay 도 이 리스를 잡는다.</b> 재제안은 배차와 똑같이 후보를 꺼내고 라이더를
 * 찜하는 일이라, dispatch-engine 의 좀비 재배차와 겹치면 같은 주문에 제안이 두 개 나간다.
 * 둘 중 진 쪽 라이더는 "이미 다른 분이 받았어요" 를 받는데 찜은 12초 동안 안 풀려서,
 * 그동안 다른 주문의 후보도 못 된다. 리스를 나눠 쓰면 그 장면 자체가 안 생긴다.
 */
@RequiredArgsConstructor
public class DispatchLease {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> releaseLockScript;
    private final OfferProperties properties;

    /** @return 잡았으면 소유자 문자열, 이미 남이 잡고 있으면 null */
    public String acquire(long orderId, String owner) {
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(RedisKeys.dispatchLock(orderId), owner, properties.leaseTtl());
        return Boolean.TRUE.equals(acquired) ? owner : null;
    }

    /**
     * @return 내가 잡은 락을 내가 풀었으면 true. false 면 TTL 이 지나 남이 가져갔다는 뜻이라
     *         그 뒤 작업을 이어가면 안 된다.
     */
    public boolean release(long orderId, String owner) {
        Long released = redis.execute(
                releaseLockScript, List.of(RedisKeys.dispatchLock(orderId)), owner);
        return released != null && released == 1L;
    }
}
