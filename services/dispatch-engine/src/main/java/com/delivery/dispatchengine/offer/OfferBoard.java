package com.delivery.dispatchengine.offer;

import com.delivery.common.RedisKeys;
import com.delivery.common.dispatch.OfferFields;
import com.delivery.common.dispatch.OfferState;
import com.delivery.dispatchengine.config.DispatchProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code dispatch:offer:{orderId}} — 제안 현황판.
 *
 * <p>락이 아니라 데이터다. 수락과 만료 중에 어느 쪽이 이겼는지 판정하는 <b>단 하나의 기준</b>이다.
 *
 * <p>왜 레디스에 있어야 하냐면, 라이더의 수락은 dispatch-engine 이 받고 10초 뒤 만료 메시지는
 * offer-relay 가 받아서다. 서로 다른 프로세스라 각자 자기 메모리에 들고 있으면 서로 뭘 아는지 모른다.
 * 그러면 라이더가 9초에 수락했는데 offer-relay 가 그걸 모르고 10초에 2순위에게 또 제안하고,
 * 2순위도 수락하면 라이더 두 명이 같은 가게에 나타난다.
 *
 * <p>부가 역할이 하나 더 있다. 배차 리스는 15초 뒤 사라지지만 이건 10분 남아 있어서,
 * 늦게 도착한 중복 메시지를 이걸로 걸러낸다.
 */
@Component
@RequiredArgsConstructor
public class OfferBoard {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> acceptOfferScript;
    private final DispatchProperties properties;

    /** @return 없으면 null (= 처음 보는 주문) */
    public OfferSnapshot read(long orderId) {
        List<Object> values = redis.opsForHash().multiGet(
                RedisKeys.offer(orderId),
                List.of(OfferFields.OFFER_ID, OfferFields.RIDER_ID, OfferFields.STATE,
                        OfferFields.ATTEMPT, OfferFields.OFFERED_AT));

        OfferState state = OfferState.parseOrNull(text(values.get(2)));
        if (state == null) {
            return null;
        }
        return new OfferSnapshot(
                number(values.get(0)), number(values.get(1)), state,
                (int) number(values.get(3)), number(values.get(4)));
    }

    public void writeOffered(long orderId, long offerId, long riderId, int attempt, long offeredAt) {
        String key = RedisKeys.offer(orderId);
        redis.opsForHash().putAll(key, Map.of(
                OfferFields.OFFER_ID, Long.toString(offerId),
                OfferFields.ORDER_ID, Long.toString(orderId),
                OfferFields.RIDER_ID, Long.toString(riderId),
                OfferFields.STATE, OfferState.OFFERED.name(),
                OfferFields.ATTEMPT, Integer.toString(attempt),
                OfferFields.OFFERED_AT, Long.toString(offeredAt)));
        // TTL 을 빼먹으면 배차가 끝난 뒤에도 영영 남는다. 지우는 코드가 안 도는 경로가 너무 많아서
        // (프로세스 죽음, 예외, 발행 실패) 지우는 쪽이 아니라 만료 쪽에 기댄다.
        redis.expire(key, properties.stateTtl());

        // 라이더는 offerId 만 들고 수락하러 온다. 보드와 같은 TTL 을 준다 —
        // 인덱스가 먼저 사라지면 보드는 멀쩡한데 찾을 길이 없어서 404 도 410 도 아닌 게 된다.
        redis.opsForValue().set(
                RedisKeys.offerIndex(offerId), Long.toString(orderId), properties.stateTtl());
    }

    /** @return 없으면 null. 인덱스 TTL 이 지났거나 아예 없던 offerId 다 */
    public Long findOrderId(long offerId) {
        String orderId = redis.opsForValue().get(RedisKeys.offerIndex(offerId));
        return orderId == null ? null : Long.valueOf(orderId);
    }

    /**
     * DE-04 수락 판정. Lua 한 번으로 읽고 비교하고 쓴다.
     *
     * <p>왜 Lua 냐면, 라이더가 9.9초에 수락하고 10.0초에 타이머가 만료되는 순간이 실제로 있어서다.
     * 자바에서 읽고 쓰면 둘 다 {@code OFFERED} 를 읽고 각자 자기 값을 써서, 나중에 쓴 쪽이 이긴다.
     * 라이더 화면엔 "배차 완료" 가 뜨는데 주문은 2순위에게 넘어가 있다.
     */
    public AcceptResult accept(long orderId, long offerId, long riderId, long acceptedAt) {
        Long returned = redis.execute(
                acceptOfferScript,
                List.of(RedisKeys.offer(orderId)),
                Long.toString(offerId), Long.toString(riderId), Long.toString(acceptedAt));
        return AcceptResult.of(returned);
    }

    public void writeState(long orderId, OfferState state) {
        String key = RedisKeys.offer(orderId);
        redis.opsForHash().put(key, OfferFields.STATE, state.name());
        redis.expire(key, properties.stateTtl());
    }

    /** 발행이 실패했을 때 되돌린다. 보드에 OFFERED 가 남아 있으면 다음 시도가 좀비로 오해한다 */
    public void clear(long orderId, long offerId) {
        redis.delete(List.of(RedisKeys.offer(orderId), RedisKeys.offerIndex(offerId)));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static long number(Object value) {
        try {
            return value == null ? 0L : Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
