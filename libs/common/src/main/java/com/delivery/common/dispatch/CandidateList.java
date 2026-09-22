package com.delivery.common.dispatch;

import com.delivery.common.RedisKeys;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code dispatch:candidates:{orderId}} — 점수순으로 줄 세운 후보 리스트.
 *
 * <p>쓰는 쪽과 읽는 쪽이 다른 서비스다. dispatch-engine 이 후보를 뽑아 채워 넣고,
 * 제안이 만료되면 offer-relay 가 앞에서부터 하나씩 꺼내 간다. 리스트를 왼쪽에서 꺼내는
 * {@code LPOP} 이 곧 "다음 후보" 라서, 누가 꺼내든 같은 사람이 두 번 나오지 않는다.
 */
@RequiredArgsConstructor
public class CandidateList {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> saveCandidatesScript;
    private final OfferProperties properties;

    /** 있던 목록을 버리고 새로 채운다 */
    public void replace(long orderId, List<Long> riderIds) {
        List<String> args = new ArrayList<>(riderIds.size() + 1);
        args.add(Long.toString(properties.stateTtl().toSeconds()));
        riderIds.forEach(riderId -> args.add(Long.toString(riderId)));

        // Object[] 로 캐스팅해야 한다. execute 의 마지막 파라미터가 Object... 라서
        // String[] 을 그냥 넘기면 "배열 하나" 인지 "펼쳐진 인자들" 인지 컴파일러가 경고를 낸다.
        redis.execute(saveCandidatesScript, List.of(RedisKeys.candidates(orderId)),
                (Object[]) args.toArray(String[]::new));
    }

    /** @return 다음 후보. 목록이 비었으면 null */
    public Long next(long orderId) {
        String riderId = redis.opsForList().leftPop(RedisKeys.candidates(orderId));
        return riderId == null ? null : Long.valueOf(riderId);
    }

    /** DE-06 디버깅용. 꺼내지 않고 남은 목록만 본다 */
    public List<String> remaining(long orderId) {
        List<String> remaining = redis.opsForList().range(RedisKeys.candidates(orderId), 0, -1);
        return remaining == null ? List.of() : remaining;
    }
}
