package com.delivery.common.dispatch;

import com.delivery.common.RedisKeys;
import com.delivery.common.Times;
import com.delivery.common.rider.RiderStateFields;
import com.delivery.common.rider.RiderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;

/**
 * {@code rider:state:{riderId}} 해시에서 <b>배차가 건드리는 부분</b>만 다룬다.
 * 좌표와 lastSeenAt 은 geo-indexer(GI-01) 몫이라 여기서 안 만진다.
 *
 * <p>왜 한 자리에 모았냐면 {@code idleSince} 때문이다. 라이더를 IDLE 로 되돌리는 자리가
 * 네 군데나 된다 — 거절(DE-05), 재제안(RE-02), 배달 완료(OR-04), 오프라인에서 복귀(GI-01).
 * 그런데 {@code status} 만 IDLE 로 바꾸고 {@code idleSince} 를 빼먹어도 <b>에러가 안 난다.</b>
 * 대기 보너스가 0이 될 뿐이라, 후보 점수에서 오래 기다린 라이더가 계속 밀리는데 아무도 모른다.
 * 자리를 하나로 만들어두면 빼먹을 자리 자체가 없어진다.
 */
@RequiredArgsConstructor
public class RiderState {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> releaseRiderScript;

    /** 제안을 보냈다. 어떤 제안인지도 같이 적어둬야 나중에 놓아줄 때 내 것인지 알 수 있다 */
    public void markOffered(long riderId, long offerId) {
        redis.opsForHash().putAll(RedisKeys.riderState(riderId), Map.of(
                RiderStateFields.STATUS, RiderStatus.OFFERED.name(),
                RiderStateFields.OFFER_ID, Long.toString(offerId)));
    }

    /** 수락해서 배달을 시작했다 (DE-04) */
    public void markDelivering(long riderId, long orderId) {
        redis.opsForHash().putAll(RedisKeys.riderState(riderId), Map.of(
                RiderStateFields.STATUS, RiderStatus.DELIVERING.name(),
                RiderStateFields.CURRENT_ORDER_ID, Long.toString(orderId),
                RiderStateFields.OFFER_ID, ""));
    }

    /**
     * 제안이 끝나 라이더를 놓아준다. 찜을 풀고 IDLE 로 되돌린다.
     *
     * <p>거절(DE-05)과 재제안(RE-02), 그리고 발행 실패(DE-03)가 전부 이걸 부른다. 둘을 따로
     * 부르게 두면 한쪽을 빼먹는데, 어느 쪽을 빼먹어도 증상이 조용하다. 찜만 풀고 상태를 안
     * 바꾸면 그 라이더는 OFFERED 로 남아 후보 검색에서 계속 걸러지고, 상태만 바꾸고 찜을 안
     * 풀면 12초 동안 다른 주문이 그 라이더를 못 잡는다. 둘 다 "라이더 한 명이 한동안 논다" 로
     * 끝나서 로그에도 안 남는다.
     *
     * <p>Lua 로 묶은 건 <b>둘 다 "내 것일 때만" 건드려야</b> 해서다. 자세한 건
     * {@code lua/release-rider.lua} 주석에 시나리오로 적어뒀다.
     *
     * @return 상태를 IDLE 로 되돌렸으면 true. false 면 그 사이 다른 주문이 이 라이더를
     *         가져갔다는 뜻이라, 건드리지 않고 넘어간 게 맞다.
     */
    public boolean release(long riderId, long orderId, long offerId) {
        Long released = redis.execute(
                releaseRiderScript,
                List.of(RedisKeys.riderState(riderId), RedisKeys.riderLock(riderId)),
                Long.toString(orderId), Long.toString(offerId),
                Long.toString(Times.now().toEpochMilli()));
        return released != null && released == 1L;
    }

    /**
     * 조건 없이 한가한 상태로 되돌린다.
     *
     * <p>배달 완료(OR-04)처럼 "이 라이더가 지금 뭘 들고 있든 끝났다" 가 확실한 자리에서만 쓴다.
     * 제안이 끝나서 놓아주는 거라면 {@link #release} 를 써야 한다 — 이건 그 사이 다른 주문이
     * 잡아간 라이더까지 한가한 걸로 덮어써서, 제안을 들고 있는 사람이 또 후보로 뽑힌다.
     */
    public void markIdle(long riderId) {
        redis.opsForHash().putAll(RedisKeys.riderState(riderId), Map.of(
                RiderStateFields.STATUS, RiderStatus.IDLE.name(),
                RiderStateFields.IDLE_SINCE, Long.toString(Times.now().toEpochMilli()),
                RiderStateFields.OFFER_ID, "",
                RiderStateFields.CURRENT_ORDER_ID, ""));
    }

    /** 거절 횟수. 지금은 점수에 반영하지 않고 지표로만 본다 (DE-05) */
    public void countReject(long riderId) {
        redis.opsForHash().increment(RedisKeys.riderState(riderId), RiderStateFields.REJECT_COUNT, 1);
    }

    /** DE-06 디버깅용 덤프 */
    public Map<Object, Object> dump(long riderId) {
        return redis.opsForHash().entries(RedisKeys.riderState(riderId));
    }
}
