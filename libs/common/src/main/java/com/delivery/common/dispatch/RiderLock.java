package com.delivery.common.dispatch;

import com.delivery.common.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * {@code lock:rider:{riderId}} — 한 라이더에게 두 주문이 동시에 제안되는 걸 막는다.
 *
 * <p>배차 리스와 모양은 같은데 <b>값에 들어가는 게 다르다.</b> 리스는 값이 인스턴스 이름이고
 * 이건 주문 아이디다. 주인이 프로세스가 아니라 주문이라서, dispatch-engine 이 잡은 찜을
 * offer-relay 가 풀 수 있다. 제안이 만료되면 그 라이더를 놓아주는 건 relay 쪽 일이다.
 *
 * <p>푸는 걸 왜 Lua 로 하냐면, {@code GET} 으로 보고 {@code DEL} 하는 사이에 남이 끼어들어서다.
 * 찜은 12초에 저절로 풀린다. 만료 메시지를 처리하던 relay 가 GET 으로 "내 주문이네" 를 확인한
 * 직후에 TTL 이 지나고, 마침 그 라이더가 다른 주문에 찜당했다면, 이어서 나가는 DEL 이
 * <b>남의 찜을 지운다.</b> 그 라이더에게 제안 두 개가 겹쳐 뜬다.
 */
@RequiredArgsConstructor
public class RiderLock {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> releaseLockScript;
    private final OfferProperties properties;

    /** @return 잡았으면 true. 이미 다른 주문이 찜해뒀으면 false */
    public boolean claim(long riderId, long orderId) {
        Boolean claimed = redis.opsForValue().setIfAbsent(
                RedisKeys.riderLock(riderId), Long.toString(orderId), properties.riderLockTtl());
        return Boolean.TRUE.equals(claimed);
    }

    /**
     * 이 주문이 잡은 찜일 때만 푼다.
     *
     * @return 풀었으면 true. false 면 이미 TTL 로 사라졌거나 다른 주문이 가져갔다는 뜻이다.
     */
    public boolean release(long riderId, long orderId) {
        Long released = redis.execute(
                releaseLockScript, List.of(RedisKeys.riderLock(riderId)), Long.toString(orderId));
        return released != null && released == 1L;
    }
}
